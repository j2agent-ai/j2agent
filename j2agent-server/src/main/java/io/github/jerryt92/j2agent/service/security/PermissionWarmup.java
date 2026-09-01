package io.github.jerryt92.j2agent.service.security;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

@Service
public class PermissionWarmup {
    private final ResourcePermissionCache cache;
    private final Set<String> loading=ConcurrentHashMap.newKeySet();
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r -> { Thread t=new Thread(r,"permission-warmup");t.setDaemon(true);return t; });
    public PermissionWarmup(ResourcePermissionCache cache) { this.cache=cache; }
    public void warm(String uid) {
        if(!loading.add(uid)) return;
        executor.submit(() -> {
            try { cache.read(uid); }
            catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("Permission warmup failed for {}",uid,e); }
            finally { loading.remove(uid); }
        });
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
