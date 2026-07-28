package io.github.jerryt92.j2agent.service.llm.queue;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.service.llm.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 输入队列后台消费者。
 *
 * <p>启动后创建固定数量守护线程，每个线程阻塞等待 ready session。
 * 真正执行前会对 {@code contextId + agentId} 加 Redis 锁，保证同一会话串行；
 * 不同会话可以由不同 worker 并行执行。</p>
 */
@Slf4j
@Service
public class ChatInputQueueWorker {

    private final ChatInputQueueManager queueManager;
    private final ChatCallbackRegistry callbackRegistry;
    private final ChatOutputDispatcher outputDispatcher;
    private final ChatService chatService;
    private final RedissonClient redissonClient;
    private final RedisKeyNamespaces redisKeyNamespaces;
    private final ChatQueueProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> workerThreads = new ArrayList<>();

    public ChatInputQueueWorker(ChatInputQueueManager queueManager,
                                ChatCallbackRegistry callbackRegistry,
                                ChatOutputDispatcher outputDispatcher,
                                ChatService chatService,
                                RedissonClient redissonClient,
                                RedisKeyNamespaces redisKeyNamespaces,
                                ChatQueueProperties properties) {
        this.queueManager = queueManager;
        this.callbackRegistry = callbackRegistry;
        this.outputDispatcher = outputDispatcher;
        this.chatService = chatService;
        this.redissonClient = redissonClient;
        this.redisKeyNamespaces = redisKeyNamespaces;
        this.properties = properties;
    }

    /**
     * 应用启动完成后再启动 worker，避免依赖 Bean 尚未就绪时消费任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        int workerCount = Math.max(1, properties.getWorkerCount());
        for (int i = 0; i < workerCount; i++) {
            Thread workerThread = new Thread(this::consumeLoop, "chat-input-queue-worker-" + i);
            workerThread.setDaemon(true);
            workerThread.start();
            workerThreads.add(workerThread);
        }
        log.info("Started {} chat input queue workers", workerCount);
    }

    /**
     * 应用关闭时中断 worker 阻塞等待，避免进程退出被后台线程拖住。
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        for (Thread workerThread : workerThreads) {
            workerThread.interrupt();
        }
    }

    /**
     * worker 主循环：消费 ready 标记、拉取任务、处理后按需重新投递 ready 标记。
     */
    private void consumeLoop() {
        while (running.get()) {
            String sessionKey = null;
            try {
                sessionKey = queueManager.takeReadySessionKey();
                ChatTurnInputTask task = queueManager.poll(sessionKey);
                if (task == null) {
                    continue;
                }
                process(sessionKey, task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warn("Chat input queue worker failed", t);
            } finally {
                if (sessionKey != null) {
                    queueManager.requeueIfPending(sessionKey);
                }
            }
        }
    }

    /**
     * 执行单条任务。queued 阶段若会话或连接已取消，直接丢弃任务。
     */
    private void process(String sessionKey, ChatTurnInputTask task) throws InterruptedException {
        if (callbackRegistry.isCancelled(task.getContextId(), task.getAgentId(), task.getSubscriptionId())) {
            log.debug("Drop queued chat task because subscription was cancelled, contextId={}, agentId={}, subscriptionId={}",
                    task.getContextId(), task.getAgentId(), task.getSubscriptionId());
            return;
        }
        RLock lock = redissonClient.getLock(redisKeyNamespaces.key("chat:input:lock:" + sessionKey));
        lock.lock();
        try {
            if (callbackRegistry.isCancelled(task.getContextId(), task.getAgentId(), task.getSubscriptionId())) {
                return;
            }
            runChatTurn(task);
        } finally {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException ignored) {
                log.debug("Chat input lock already released, sessionKey={}", sessionKey);
            }
        }
    }

    /**
     * 将后台 worker 与现有 ChatService 回调模型桥接起来。
     *
     * <p>ChatService 仍通过 callback 输出事件；worker callback 会把事件交给
     * {@link ChatOutputDispatcher} 做 session 级广播，从而支持多客户端观察同一后台任务。</p>
     */
    private void runChatTurn(ChatTurnInputTask task) throws InterruptedException {
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean terminalSignalled = new AtomicBoolean(false);
        ChatCallback<AgentUiEventEnvelope> callback = new ChatCallback<>(task.getSubscriptionId());
        callback.responseCall = event -> outputDispatcher.dispatchToSession(
                task.getContextId(), task.getAgentId(), event);
        callback.completeCall = () -> {
            try {
                outputDispatcher.completeSession(task.getContextId(), task.getAgentId());
            } finally {
                signalTerminal(terminal, terminalSignalled);
            }
        };
        callback.errorCall = error -> {
            try {
                outputDispatcher.fail(task.getContextId(), task.getAgentId(), task.getSubscriptionId(),
                        "internalError", error);
            } finally {
                signalTerminal(terminal, terminalSignalled);
            }
        };
        callback.timeoutCall = () -> signalTerminal(terminal, terminalSignalled);

        try {
            chatService.handleChat(callback, task.getRequest(), task.getUserContext(), task.getAgentId());
            callbackRegistry.bindWebsocketCloseHandler(
                    task.getContextId(), task.getAgentId(), task.getSubscriptionId(), callback.onWebsocketClose);
        } catch (Throwable t) {
            outputDispatcher.fail(task.getContextId(), task.getAgentId(), task.getSubscriptionId(),
                    "internalError", t);
            signalTerminal(terminal, terminalSignalled);
        }
        terminal.await();
        callbackRegistry.bindWebsocketCloseHandler(
                task.getContextId(), task.getAgentId(), task.getSubscriptionId(), null);
    }

    private static void signalTerminal(CountDownLatch terminal, AtomicBoolean terminalSignalled) {
        if (terminalSignalled.compareAndSet(false, true)) {
            terminal.countDown();
        }
    }
}
