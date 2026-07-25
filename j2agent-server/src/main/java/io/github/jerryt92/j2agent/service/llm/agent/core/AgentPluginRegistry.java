package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.config.plugin.PluginLayout;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * 外部 Agent 插件注册表：启动期与运行时从 {@link PluginProperties#getPath()} 加载/卸载。
 * 支持 {@code <plugin.path>/agents/<agentDir>/} 标准布局（{@code plugin.path} 为 {@code .../plugins} 根目录），
 * 以及根目录裸 JAR（兼容）。
 * 扫描并注册 JAR 内全部 Spring 组件（{@code @Component} 等），不仅限于 {@link AiAgent}；启动时仅预实例化 Agent。
 * 依赖 {@link PluginAgentClassLoader} 保证平台类与 Spring 容器一致，实例化统一走 {@code getBean}。
 * 启动期在 {@link ApplicationReadyEvent} 之后再 {@code getBean}，避免早于 {@code baseTools} 等单例就绪。
 */
@Slf4j
@Component
public class AgentPluginRegistry implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware,
        ApplicationListener<ApplicationReadyEvent> {

    private volatile boolean startupActivated;

    private ApplicationContext applicationContext;
    private final List<DynamicRegistration> dynamicRegistrations = new CopyOnWriteArrayList<>();
    private final Object reloadLock = new Object();
    private final SpringObjenesis agentIdProbeObjenesis = new SpringObjenesis();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (applicationContext == null) {
            log.warn("ApplicationContext not ready, skip plugin load at registry phase.");
            return;
        }
        synchronized (reloadLock) {
            loadJarsAtStartup(registry);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 插件 Bean 在 ApplicationReady 之后实例化，此处仅注册 BeanDefinition
    }

    /**
     * 应用就绪后再加载插件 Agent，与手动 reload 时机一致（容器依赖已就绪）。
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (startupActivated) {
            return;
        }
        startupActivated = true;
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        activatePluginAgents(beanFactory, true);
        applicationContext.getBean(AgentRouter.class).refresh();
        applicationContext.getBean(io.github.jerryt92.j2agent.service.rag.SimpleRagStoreSyncService.class)
                .synchronizeSimpleRagRetrievers();
        log.info("Plugin agents activated on application ready.");
    }

    /**
     * 查询已加载插件 Agent 状态。
     */
    public AgentPluginStatus getStatus() {
        String pluginPath = resolvePluginPath();
        List<String> jarFiles = listPluginBundleLabels(pluginPath);
        List<String> loadedAgentIds = dynamicRegistrations.stream()
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .sorted()
                .collect(Collectors.toList());
        List<InstalledPackageInfo> packages = listInstalledPackages();
        return new AgentPluginStatus(jarFiles, loadedAgentIds, packages);
    }

    /**
     * 在 reload 互斥锁下执行安装/删除等文件系统变更，避免与热重载竞态。
     */
    public <T> T runExclusive(java.util.concurrent.Callable<T> action) throws Exception {
        synchronized (reloadLock) {
            return action.call();
        }
    }

    /**
     * 卸载全部插件 Bean 并关闭其 ClassLoader。
     * Windows 下 JAR 被 ClassLoader 持有文件锁，删除/替换插件目录前必须先调用本方法释放锁；
     * 调用方随后应执行 {@code reload()} 重新加载剩余插件。
     */
    public void unloadPlugins() {
        synchronized (reloadLock) {
            unloadDynamicPlugins();
        }
    }

    /**
     * 列出 agents 目录下已安装插件包及预扫描 agentId。
     */
    public List<InstalledPackageInfo> listInstalledPackages() {
        String pluginPath = resolvePluginPath();
        if (!StringUtils.hasText(pluginPath)) {
            return List.of();
        }
        File agentsRoot = AgentPluginBundle.resolveAgentsRoot(pluginPath);
        if (!agentsRoot.isDirectory()) {
            return List.of();
        }
        Set<String> loadedAgentIds = dynamicRegistrations.stream()
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        DefaultListableBeanFactory beanFactory = applicationContext != null
                ? (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory()
                : null;
        List<InstalledPackageInfo> packages = new ArrayList<>();
        File[] subdirs = agentsRoot.listFiles(File::isDirectory);
        if (subdirs == null) {
            return List.of();
        }
        for (File dir : subdirs) {
            if (Set.of(PluginLayout.SKILLS_DIR_NAME, PluginLayout.AGENTS_DIR_NAME).contains(dir.getName())) {
                continue;
            }
            try {
                AgentPluginBundle bundle = AgentPluginBundle.fromAgentDirectory(dir);
                List<String> agentIds = scanBundleAgentIds(bundle, beanFactory);
                boolean loaded = !agentIds.isEmpty() && loadedAgentIds.containsAll(agentIds);
                packages.add(new InstalledPackageInfo(
                        dir.getName(),
                        bundle.jarFile().getName(),
                        agentIds,
                        loaded));
            } catch (IllegalArgumentException ex) {
                log.debug("Skip invalid agent install directory {}: {}", dir.getName(), ex.getMessage());
            }
        }
        packages.sort(java.util.Comparator.comparing(InstalledPackageInfo::agentDir));
        return packages;
    }

    /**
     * 预扫描 bundle 内具体 {@link AiAgent} 实现类（不注册 Bean）。
     */
    public List<Class<?>> scanBundleAgentClasses(AgentPluginBundle bundle) {
        if (applicationContext == null) {
            return List.of();
        }
        File jarFile = bundle.jarFile();
        PluginAgentClassLoader scanLoader = null;
        try (JarFile jar = new JarFile(jarFile)) {
            scanLoader = new PluginAgentClassLoader(
                    bundle.toClassLoaderUrls(), applicationContext.getClassLoader());
            return scanAiAgentClasses(jar, scanLoader);
        } catch (Exception ex) {
            log.warn("Failed to scan AiAgent classes for bundle {}: {}", bundle.label(), ex.getMessage());
            return List.of();
        } finally {
            closeQuietly(scanLoader);
        }
    }

    /**
     * 预扫描 bundle 内 AiAgent 的 agentId（不注册 Bean）。
     */
    public List<String> scanBundleAgentIds(AgentPluginBundle bundle) {
        DefaultListableBeanFactory beanFactory = applicationContext != null
                ? (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory()
                : null;
        return scanBundleAgentIds(bundle, beanFactory);
    }

    /**
     * 当前容器内置（非插件）agentId 集合。
     */
    public Set<String> getBuiltinAgentIds() {
        if (applicationContext == null) {
            return Set.of();
        }
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        return collectBuiltinAgentIds(beanFactory);
    }

    private List<String> scanBundleAgentIds(AgentPluginBundle bundle, DefaultListableBeanFactory beanFactory) {
        if (applicationContext == null) {
            return List.of();
        }
        File jarFile = bundle.jarFile();
        PluginAgentClassLoader scanLoader = null;
        try (JarFile jar = new JarFile(jarFile)) {
            scanLoader = new PluginAgentClassLoader(
                    bundle.toClassLoaderUrls(), applicationContext.getClassLoader());
            List<String> agentIds = new ArrayList<>();
            for (Class<?> agentClass : scanAiAgentClasses(jar, scanLoader)) {
                String agentId = resolveAgentId(agentClass, beanFactory);
                if (StringUtils.hasText(agentId)) {
                    agentIds.add(agentId);
                }
            }
            agentIds.sort(String::compareTo);
            return agentIds;
        } catch (Exception ex) {
            log.warn("Failed to scan agentIds for bundle {}: {}", bundle.label(), ex.getMessage());
            return List.of();
        } finally {
            closeQuietly(scanLoader);
        }
    }

    /**
     * 重新扫描插件目录并热加载 JAR；与内置 Agent agentId 冲突时整次失败且不替换已加载插件。
     */
    public AgentPluginReloadOutcome reload() {
        synchronized (reloadLock) {
            String pluginPath = resolvePluginPath();
            List<AgentPluginBundle> bundles = listPluginBundles(pluginPath);
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
            Set<String> builtinAgentIds = collectBuiltinAgentIds(beanFactory);
            try {
                validatePluginAgentIds(bundles, builtinAgentIds, beanFactory);
            } catch (AgentPluginConflictException ex) {
                log.warn("Agent plugin reload rejected: {}", ex.getMessage());
                return AgentPluginReloadOutcome.failure(listPluginBundleLabels(pluginPath),
                        currentLoadedAgentIds(), ex.getMessage());
            }
            unloadDynamicPlugins();
            for (AgentPluginBundle bundle : bundles) {
                try {
                    scanAndRegisterBundle(bundle, beanFactory);
                } catch (Exception ex) {
                    log.error("Skip failed plugin bundle during reload scan: {}", bundle.jarFile().getAbsolutePath(), ex);
                }
            }
            List<String> loadedAgentIds = activateAllPluginBeans(beanFactory);
            return AgentPluginReloadOutcome.success(listPluginBundleLabels(pluginPath), loadedAgentIds);
        }
    }

    /**
     * 仅热重载指定安装目录下的插件包，其它已加载插件保持不变。
     */
    public AgentPluginReloadOutcome reloadPackage(String agentDir) {
        synchronized (reloadLock) {
            String pluginPath = resolvePluginPath();
            List<String> jarLabels = listPluginBundleLabels(pluginPath);
            try {
                validateAgentDirName(agentDir);
            } catch (IllegalArgumentException ex) {
                return AgentPluginReloadOutcome.failure(jarLabels, currentLoadedAgentIds(), ex.getMessage());
            }
            if (!StringUtils.hasText(pluginPath)) {
                return AgentPluginReloadOutcome.failure(jarLabels, currentLoadedAgentIds(),
                        "Plugin path is not configured");
            }
            File agentsRoot = AgentPluginBundle.resolveAgentsRoot(pluginPath);
            File targetDir = new File(agentsRoot, agentDir);
            if (!targetDir.isDirectory()) {
                return AgentPluginReloadOutcome.failure(jarLabels, currentLoadedAgentIds(),
                        "Agent package directory not found: " + agentDir);
            }
            AgentPluginBundle bundle;
            try {
                bundle = AgentPluginBundle.fromAgentDirectory(targetDir);
            } catch (IllegalArgumentException ex) {
                return AgentPluginReloadOutcome.failure(jarLabels, currentLoadedAgentIds(), ex.getMessage());
            }
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
            Set<String> builtinAgentIds = collectBuiltinAgentIds(beanFactory);
            try {
                validateSinglePackageAgentIds(bundle, agentDir, builtinAgentIds, beanFactory);
            } catch (AgentPluginConflictException ex) {
                log.warn("Agent plugin package reload rejected: {}", ex.getMessage());
                return AgentPluginReloadOutcome.failure(jarLabels, currentLoadedAgentIds(), ex.getMessage());
            }
            unloadBundle(agentDir);
            try {
                scanAndRegisterBundle(bundle, beanFactory);
            } catch (Exception ex) {
                log.error("Failed to reload plugin package {}: {}", agentDir, ex.getMessage(), ex);
                return AgentPluginReloadOutcome.failure(listPluginBundleLabels(pluginPath),
                        currentLoadedAgentIds(),
                        ex.getMessage() != null ? ex.getMessage() : "Failed to reload plugin package");
            }
            List<String> loadedAgentIds = activateAllPluginBeans(beanFactory);
            log.info("Reloaded plugin package: agentDir={}, loadedAgentIds={}", agentDir, loadedAgentIds);
            return AgentPluginReloadOutcome.success(listPluginBundleLabels(pluginPath), loadedAgentIds);
        }
    }

    /**
     * 返回指定安装目录当前已加载的 agentId（用于可选重建 SimpleRag）。
     */
    public List<String> getLoadedAgentIdsForPackage(String agentDir) {
        if (!StringUtils.hasText(agentDir)) {
            return List.of();
        }
        return dynamicRegistrations.stream()
                .filter(r -> r.aiAgent() && matchesAgentDir(r.jarFileName(), agentDir))
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 当前由本注册表加载的动态 Bean 名称集合。
     */
    public Set<String> getDynamicBeanNames() {
        return dynamicRegistrations.stream()
                .map(DynamicRegistration::beanName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void loadJarsAtStartup(BeanDefinitionRegistry registry) {
        String pluginPath = resolvePluginPath();
        if (!StringUtils.hasText(pluginPath)) {
            log.info("No plugin path configured, skip dynamic loading.");
            return;
        }
        List<AgentPluginBundle> bundles = listPluginBundles(pluginPath);
        if (bundles.isEmpty()) {
            log.info("No plugin bundles found in plugin path: {}", pluginPath);
            return;
        }
        for (AgentPluginBundle bundle : bundles) {
            try {
                scanAndRegisterBundle(bundle, registry);
            } catch (Exception ex) {
                log.error("Skip failed plugin bundle during startup scan: {}", bundle.jarFile().getAbsolutePath(), ex);
            }
        }
    }

    /**
     * 为已登记的插件 BeanDefinition 执行 Spring 注入与初始化。
     */
    private void activatePluginAgents(DefaultListableBeanFactory beanFactory, boolean failFastOnStartup) {
        if (!dynamicRegistrations.isEmpty()) {
            try {
                validatePluginAgentIds(
                        listPluginBundles(resolvePluginPath()),
                        collectBuiltinAgentIds(beanFactory),
                        beanFactory);
            } catch (AgentPluginConflictException ex) {
                throw new IllegalStateException("Agent plugin startup load failed: " + ex.getMessage(), ex);
            }
        }
        activateAllPluginBeans(beanFactory);
    }

    /**
     * 实例化已注册的插件 Bean：先依赖类，再 AiAgent。
     */
    private List<String> activateAllPluginBeans(DefaultListableBeanFactory beanFactory) {
        activatePluginDependencyBeans(beanFactory);
        List<String> agentIds = new ArrayList<>();
        for (DynamicRegistration registration : new ArrayList<>(dynamicRegistrations)) {
            if (!registration.aiAgent()) {
                continue;
            }
            try {
                if (beanFactory.containsSingleton(registration.beanName())) {
                    Object existing = beanFactory.getBean(registration.beanName());
                    if (existing instanceof AiAgent aiAgent) {
                        registration.setAgentId(aiAgent.getAgentId());
                        agentIds.add(aiAgent.getAgentId());
                    }
                    continue;
                }
                AiAgent aiAgent = obtainPluginAgent(beanFactory, registration.beanName());
                registration.setAgentId(aiAgent.getAgentId());
                agentIds.add(aiAgent.getAgentId());
                log.info("Loaded plugin agent: agentId={}, bean={}, jar={}",
                        aiAgent.getAgentId(), registration.beanName(), registration.jarFileName());
            } catch (Exception ex) {
                log.error("Skip failed plugin agent bean: bean={}, jar={}",
                        registration.beanName(), registration.jarFileName(), ex);
            }
        }
        return agentIds;
    }

    /**
     * 先实例化插件依赖 Bean（如 IntelligentReportSqlTools），再创建 AiAgent，避免构造器注入找不到 Bean。
     */
    private void activatePluginDependencyBeans(DefaultListableBeanFactory beanFactory) {
        activatePluginDependencyBeans(beanFactory, dynamicRegistrations);
    }

    private void activatePluginDependencyBeans(DefaultListableBeanFactory beanFactory,
                                               Iterable<DynamicRegistration> registrations) {
        for (DynamicRegistration registration : registrations) {
            if (registration.aiAgent()) {
                continue;
            }
            if (beanFactory.containsSingleton(registration.beanName())) {
                continue;
            }
            try {
                beanFactory.getBean(registration.beanName());
                log.info("Initialized plugin dependency bean: {} (jar={})",
                        registration.beanName(), registration.jarFileName());
            } catch (Exception ex) {
                log.error("Skip failed plugin dependency bean: bean={}, jar={}",
                        registration.beanName(), registration.jarFileName(), ex);
            }
        }
    }

    /**
     * 扫描插件 bundle，将 JAR 内 Spring 组件注册为 BeanDefinition（构造器自动装配）。
     */
    private List<DynamicRegistration> scanAndRegisterBundle(AgentPluginBundle bundle, BeanDefinitionRegistry registry) {
        File jarFile = bundle.jarFile();
        log.info("Loading plugin bundle: {} ({})", bundle.label(), jarFile.getAbsolutePath());
        List<DynamicRegistration> added = new ArrayList<>();
        PluginAgentClassLoader classLoader = null;
        try (JarFile jar = new JarFile(jarFile)) {
            classLoader = new PluginAgentClassLoader(
                    bundle.toClassLoaderUrls(), applicationContext.getClassLoader());
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                String className = entry.getName().replace('/', '.')
                        .substring(0, entry.getName().length() - 6);
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (!isSpringComponent(clazz)) {
                        continue;
                    }
                    String beanName = resolveBeanName(clazz, registry);
                    if (isDuplicateRegistration(beanName)) {
                        continue;
                    }
                    if (registry.containsBeanDefinition(beanName)
                            && !replaceExistingPluginBeanDefinition(registry, beanName)) {
                        log.info("Skip plugin class {} — non-plugin bean {} already exists",
                                clazz.getName(), beanName);
                        continue;
                    }
                    GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
                    beanDefinition.setBeanClass(clazz);
                    beanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                    beanDefinition.setAutowireCandidate(true);
                    beanDefinition.setLazyInit(true);
                    registry.registerBeanDefinition(beanName, beanDefinition);
                    boolean aiAgent = isConcreteAiAgent(clazz);
                    DynamicRegistration registration =
                            new DynamicRegistration(beanName, clazz.getName(), classLoader, bundle.label(), aiAgent);
                    dynamicRegistrations.add(registration);
                    added.add(registration);
                    log.info("Registered dynamic plugin bean definition: {} from {} (aiAgent={})",
                            beanName, clazz.getName(), aiAgent);
                } catch (Throwable e) {
                    log.error("", e);
                }
            }
        } catch (Exception e) {
            closeQuietly(classLoader);
            throw new IllegalStateException("Failed to load JAR: " + jarFile.getAbsolutePath(), e);
        }
        if (added.isEmpty()) {
            closeQuietly(classLoader);
        }
        return added;
    }

    /**
     * 热加载时替换同名的旧插件 Bean 定义，避免 ClassLoader 切换后类型不一致。
     *
     * @return true 表示已移除旧定义可继续注册；false 表示存在内置等非插件 Bean 占用该 beanName，应跳过
     */
    private boolean replaceExistingPluginBeanDefinition(BeanDefinitionRegistry registry, String beanName) {
        BeanDefinition existing = registry.getBeanDefinition(beanName);
        if (!(existing instanceof GenericBeanDefinition generic) || !generic.isLazyInit()) {
            return false;
        }
        removeBeanDefinition(registry, beanName);
        log.info("Replaced previous plugin bean definition: {}", beanName);
        return true;
    }

    private void removeBeanDefinition(BeanDefinitionRegistry registry, String beanName) {
        dynamicRegistrations.stream()
                .filter(registration -> registration.beanName().equals(beanName))
                .findFirst()
                .ifPresent(this::removeDynamicEventListeners);
        if (registry instanceof DefaultListableBeanFactory beanFactory) {
            if (beanFactory.containsSingleton(beanName)) {
                beanFactory.destroySingleton(beanName);
            }
        }
        if (registry.containsBeanDefinition(beanName)) {
            registry.removeBeanDefinition(beanName);
        }
    }

    /**
     * 交由 Spring 容器完成构造器注入、字段注入与初始化。
     */
    private AiAgent obtainPluginAgent(DefaultListableBeanFactory beanFactory, String beanName) {
        Object bean = beanFactory.getBean(beanName);
        if (!(bean instanceof AiAgent aiAgent)) {
            throw new IllegalStateException("Plugin bean is not AiAgent: " + beanName);
        }
        return aiAgent;
    }

    private void unloadDynamicPlugins() {
        unloadRegistrations(new ArrayList<>(dynamicRegistrations));
        dynamicRegistrations.clear();
    }

    /**
     * 卸载指定安装目录对应的动态 Bean 与 ClassLoader。
     */
    private void unloadBundle(String agentDir) {
        List<DynamicRegistration> toUnload = dynamicRegistrations.stream()
                .filter(r -> matchesAgentDir(r.jarFileName(), agentDir))
                .toList();
        if (toUnload.isEmpty()) {
            log.info("No loaded registrations to unload for agentDir={}", agentDir);
            return;
        }
        unloadRegistrations(toUnload);
        dynamicRegistrations.removeIf(r -> matchesAgentDir(r.jarFileName(), agentDir));
    }

    /**
     * 销毁登记的动态 Bean 并关闭其 ClassLoader（先 Agent 后依赖）。
     */
    private void unloadRegistrations(List<DynamicRegistration> registrations) {
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        List<DynamicRegistration> snapshot = new ArrayList<>(registrations);
        snapshot.sort((a, b) -> Boolean.compare(b.aiAgent(), a.aiAgent()));
        Set<URLClassLoader> classLoaders = new LinkedHashSet<>();
        for (DynamicRegistration registration : snapshot) {
            String beanName = registration.beanName();
            try {
                removeDynamicEventListeners(registration);
                if (beanFactory.containsSingleton(beanName)) {
                    beanFactory.destroySingleton(beanName);
                }
                if (beanFactory.containsBeanDefinition(beanName)) {
                    beanFactory.removeBeanDefinition(beanName);
                }
            } catch (Exception ex) {
                log.warn("Failed to remove dynamic bean: {}", beanName, ex);
            }
            if (registration.classLoader() != null) {
                classLoaders.add(registration.classLoader());
            }
        }
        classLoaders.forEach(AgentPluginRegistry::closeQuietly);
    }

    /**
     * {@code @EventListener} 方法会以 {@link ApplicationListenerMethodAdapter} 常驻事件广播器。
     * 动态插件热卸载只移除 BeanDefinition 不会自动摘除这些适配器，旧 ClassLoader 中的 Method
     * 会在下一次事件发布时尝试调用同名新 Bean，触发 Spring 的目标类型断言异常。
     */
    private void removeDynamicEventListeners(DynamicRegistration registration) {
        if (applicationContext == null) {
            return;
        }
        try {
            ApplicationEventMulticaster multicaster = applicationContext.getBean(
                    AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                    ApplicationEventMulticaster.class);
            multicaster.removeApplicationListenerBean(registration.beanName());
            multicaster.removeApplicationListeners(listener -> isListenerForDynamicRegistration(listener, registration));
        } catch (Exception ex) {
            log.debug("Failed to remove dynamic event listeners for bean {}: {}",
                    registration.beanName(), ex.getMessage());
        }
    }

    private static boolean isListenerForDynamicRegistration(ApplicationListener<?> listener,
                                                           DynamicRegistration registration) {
        if (!(listener instanceof ApplicationListenerMethodAdapter adapter)) {
            return false;
        }
        Method targetMethod = resolveListenerTargetMethod(adapter);
        if (targetMethod != null) {
            Class<?> declaringClass = targetMethod.getDeclaringClass();
            return declaringClass.getClassLoader() == registration.classLoader()
                    || declaringClass.getName().equals(registration.className());
        }
        String listenerId = adapter.getListenerId();
        return listenerId != null && listenerId.startsWith(registration.className() + ".");
    }

    private static Method resolveListenerTargetMethod(ApplicationListenerMethodAdapter adapter) {
        try {
            Method accessor = ApplicationListenerMethodAdapter.class.getDeclaredMethod("getTargetMethod");
            accessor.setAccessible(true);
            Object targetMethod = accessor.invoke(adapter);
            return targetMethod instanceof Method method ? method : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * jar 标签是否属于指定 agentDir（支持 agents/{dir}/jar 与 {dir}/jar）。
     */
    static boolean matchesAgentDir(String jarFileName, String agentDir) {
        if (!StringUtils.hasText(jarFileName) || !StringUtils.hasText(agentDir)) {
            return false;
        }
        String marker = agentDir + "/";
        return jarFileName.startsWith(marker) || jarFileName.contains("/" + marker);
    }

    /**
     * 校验单包 agentId：不与内置冲突，也不与其它已加载插件冲突。
     */
    private void validateSinglePackageAgentIds(AgentPluginBundle bundle,
                                               String agentDir,
                                               Set<String> builtinAgentIds,
                                               DefaultListableBeanFactory beanFactory)
            throws AgentPluginConflictException {
        Set<String> otherLoadedIds = dynamicRegistrations.stream()
                .filter(r -> r.aiAgent() && !matchesAgentDir(r.jarFileName(), agentDir))
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> packageIds = new LinkedHashSet<>();
        PluginAgentClassLoader scanLoader = null;
        try (JarFile jar = new JarFile(bundle.jarFile())) {
            scanLoader = new PluginAgentClassLoader(
                    bundle.toClassLoaderUrls(), applicationContext.getClassLoader());
            for (Class<?> agentClass : scanAiAgentClasses(jar, scanLoader)) {
                String agentId = resolveAgentId(agentClass, beanFactory);
                if (!StringUtils.hasText(agentId)) {
                    log.warn("Skip plugin agentId validation for {} in {} — cannot resolve agentId",
                            agentClass.getName(), bundle.label());
                    continue;
                }
                if (builtinAgentIds.contains(agentId)) {
                    throw new AgentPluginConflictException(
                            "Plugin agentId conflicts with built-in agent: " + agentId
                                    + " (class=" + agentClass.getName() + ", bundle=" + bundle.label() + ")");
                }
                if (!packageIds.add(agentId)) {
                    throw new AgentPluginConflictException(
                            "Duplicate plugin agentId in package: " + agentId);
                }
                if (otherLoadedIds.contains(agentId)) {
                    throw new AgentPluginConflictException(
                            "Plugin agentId already loaded by another package: " + agentId);
                }
            }
        } catch (AgentPluginConflictException conflict) {
            throw conflict;
        } catch (Exception ex) {
            if (ex instanceof MalformedURLException urlEx) {
                throw new IllegalStateException("Failed to validate plugin bundle URLs: " + bundle.label(), urlEx);
            }
            throw new IllegalStateException("Failed to validate plugin JAR: " + bundle.jarFile().getAbsolutePath(), ex);
        } finally {
            closeQuietly(scanLoader);
        }
    }

    private static void validateAgentDirName(String agentDir) {
        if (!StringUtils.hasText(agentDir) || agentDir.contains("..") || agentDir.contains("/") || agentDir.contains("\\")) {
            throw new IllegalArgumentException("Invalid agent directory name: " + agentDir);
        }
        if (PluginLayout.AGENTS_DIR_NAME.equals(agentDir) || PluginLayout.SKILLS_DIR_NAME.equals(agentDir)) {
            throw new IllegalArgumentException("Reserved agent directory name: " + agentDir);
        }
    }

    private void validatePluginAgentIds(List<AgentPluginBundle> bundles, Set<String> builtinAgentIds,
                                        DefaultListableBeanFactory beanFactory)
            throws AgentPluginConflictException {
        Set<String> seenPluginIds = new LinkedHashSet<>();
        for (AgentPluginBundle bundle : bundles) {
            File jarFile = bundle.jarFile();
            PluginAgentClassLoader scanLoader = null;
            try (JarFile jar = new JarFile(jarFile)) {
                scanLoader = new PluginAgentClassLoader(
                        bundle.toClassLoaderUrls(), applicationContext.getClassLoader());
                for (Class<?> agentClass : scanAiAgentClasses(jar, scanLoader)) {
                    String agentId = resolveAgentId(agentClass, beanFactory);
                    if (!StringUtils.hasText(agentId)) {
                        log.warn("Skip plugin agentId validation for {} in {} — cannot resolve agentId",
                                agentClass.getName(), bundle.label());
                        continue;
                    }
                    if (builtinAgentIds.contains(agentId)) {
                        throw new AgentPluginConflictException(
                                "Plugin agentId conflicts with built-in agent: " + agentId
                                        + " (class=" + agentClass.getName() + ", bundle=" + bundle.label() + ")");
                    }
                    if (!seenPluginIds.add(agentId)) {
                        throw new AgentPluginConflictException(
                                "Duplicate plugin agentId in plugin directory: " + agentId);
                    }
                }
            } catch (Exception ex) {
                if (ex instanceof AgentPluginConflictException conflict) {
                    throw conflict;
                }
                if (ex instanceof MalformedURLException urlEx) {
                    throw new IllegalStateException("Failed to validate plugin bundle URLs: " + bundle.label(), urlEx);
                }
                throw new IllegalStateException("Failed to validate plugin JAR: " + jarFile.getAbsolutePath(), ex);
            } finally {
                closeQuietly(scanLoader);
            }
        }
    }

    private Set<String> collectBuiltinAgentIds(DefaultListableBeanFactory beanFactory) {
        Set<String> ids = new LinkedHashSet<>();
        if (applicationContext != null) {
            try {
                applicationContext.getBeansOfType(AiAgent.class).forEach((name, agent) -> {
                    if (!getDynamicBeanNames().contains(name)) {
                        ids.add(agent.getAgentId());
                    }
                });
            } catch (BeansException ignored) {
                // 启动早期容器尚未完成实例化
            }
        }
        return ids;
    }

    private List<Class<?>> scanAiAgentClasses(JarFile jar, PluginAgentClassLoader classLoader) {
        List<Class<?>> result = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            String className = entry.getName().replace('/', '.')
                    .substring(0, entry.getName().length() - 6);
            try {
                Class<?> clazz = classLoader.loadClass(className);
                if (isConcreteAiAgent(clazz)) {
                    result.add(clazz);
                }
            } catch (Throwable ignored) {
                // 预扫描忽略无法加载的类
            }
        }
        return result;
    }

    /**
     * 预扫描 agentId：优先 Spring 构造器注入；插件内 Bean 尚未注册时回退到无构造调用（仅读取 getAgentId）。
     */
    private String resolveAgentId(Class<?> clazz, DefaultListableBeanFactory beanFactory) {
        if (!AiAgent.class.isAssignableFrom(clazz)) {
            return null;
        }
        if (beanFactory != null) {
            try {
                Object probe = beanFactory.autowire(clazz, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
                if (probe instanceof AiAgent aiAgent) {
                    return aiAgent.getAgentId();
                }
            } catch (Exception ex) {
                log.debug("Cannot resolve agentId via autowire for {}: {}", clazz.getName(), ex.getMessage());
            }
        }
        return resolveAgentIdWithoutConstruction(clazz);
    }

    /**
     * 插件 Agent 常依赖同 JAR 内其它 Bean（如 Retriever），安装预扫描时无法完成构造器注入；
     * getAgentId() 通常为常量，可无构造实例化后读取。
     */
    private String resolveAgentIdWithoutConstruction(Class<?> clazz) {
        try {
            Object probe = agentIdProbeObjenesis.newInstance(clazz);
            if (probe instanceof AiAgent aiAgent) {
                return aiAgent.getAgentId();
            }
        } catch (Exception ex) {
            log.debug("Cannot resolve agentId without construction for {}: {}", clazz.getName(), ex.getMessage());
        }
        return null;
    }

    private boolean isDuplicateRegistration(String beanName) {
        return dynamicRegistrations.stream().anyMatch(r -> r.beanName().equals(beanName));
    }

    private String resolvePluginPath() {
        if (applicationContext == null) {
            return null;
        }
        // 启动早期 BeanDefinition 注册阶段不 getBean(PluginProperties)，避免过早实例化配置 Bean。
        return applicationContext.getEnvironment().getProperty("j2agent.plugin.path");
    }

    private static List<AgentPluginBundle> listPluginBundles(String pluginPath) {
        return AgentPluginBundle.discover(pluginPath);
    }

    private static List<String> listPluginBundleLabels(String pluginPath) {
        return listPluginBundles(pluginPath).stream()
                .map(AgentPluginBundle::label)
                .collect(Collectors.toList());
    }

    private List<String> currentLoadedAgentIds() {
        return dynamicRegistrations.stream()
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 解析插件 Bean 名称，优先使用 {@code @Component} 等注解的 value（如 intelligentReportKbRetriever）。
     */
    private String resolveBeanName(Class<?> clazz, BeanDefinitionRegistry registry) {
        String beanName = resolveStereotypeBeanName(clazz);
        if (registry.containsBeanDefinition(beanName)) {
            String fqcn = clazz.getName();
            if (!registry.containsBeanDefinition(fqcn)) {
                return fqcn;
            }
        }
        return beanName;
    }

    private static String resolveStereotypeBeanName(Class<?> clazz) {
        Component component = clazz.getAnnotation(Component.class);
        if (component != null && StringUtils.hasText(component.value())) {
            return component.value();
        }
        Service service = clazz.getAnnotation(Service.class);
        if (service != null && StringUtils.hasText(service.value())) {
            return service.value();
        }
        Repository repository = clazz.getAnnotation(Repository.class);
        if (repository != null && StringUtils.hasText(repository.value())) {
            return repository.value();
        }
        RestController restController = clazz.getAnnotation(RestController.class);
        if (restController != null && StringUtils.hasText(restController.value())) {
            return restController.value();
        }
        return StringUtils.uncapitalize(clazz.getSimpleName());
    }

    /**
     * 是否为可注册的 Spring 风格 Bean（与 {@code @ComponentScan} 常见 stereotype 对齐）。
     */
    private static boolean isSpringComponent(Class<?> clazz) {
        return !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers())
                && (clazz.isAnnotationPresent(org.springframework.stereotype.Component.class)
                || clazz.isAnnotationPresent(org.springframework.context.annotation.Configuration.class)
                || clazz.isAnnotationPresent(org.springframework.stereotype.Service.class)
                || clazz.isAnnotationPresent(org.springframework.stereotype.Repository.class)
                || clazz.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class));
    }

    private static boolean isConcreteAiAgent(Class<?> clazz) {
        return AiAgent.class.isAssignableFrom(clazz)
                && !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException ex) {
            log.debug("Failed to close plugin ClassLoader: {}", ex.getMessage());
        }
    }

    /**
     * 动态插件 Bean 登记信息。
     */
    static final class DynamicRegistration {
        private final String beanName;
        private final String className;
        private final PluginAgentClassLoader classLoader;
        private final String jarFileName;
        private final boolean aiAgent;
        private volatile String agentId;

        DynamicRegistration(String beanName,
                            String className,
                            PluginAgentClassLoader classLoader,
                            String jarFileName,
                            boolean aiAgent) {
            this.beanName = beanName;
            this.className = className;
            this.classLoader = classLoader;
            this.jarFileName = jarFileName;
            this.aiAgent = aiAgent;
        }

        boolean aiAgent() {
            return aiAgent;
        }

        String beanName() {
            return beanName;
        }

        String className() {
            return className;
        }

        PluginAgentClassLoader classLoader() {
            return classLoader;
        }

        String jarFileName() {
            return jarFileName;
        }

        String agentId() {
            return agentId;
        }

        void setAgentId(String agentId) {
            this.agentId = agentId;
        }
    }

    /**
     * 已安装插件包信息。
     */
    public record InstalledPackageInfo(
            String agentDir,
            String jarName,
            List<String> agentIds,
            boolean loaded) {
    }

    /**
     * 插件状态。
     */
    public record AgentPluginStatus(
            List<String> jarFiles,
            List<String> loadedAgentIds,
            List<InstalledPackageInfo> packages) {
    }

    /**
     * 插件重载结果。
     */
    public record AgentPluginReloadOutcome(
            boolean success,
            String message,
            List<String> jarFiles,
            List<String> loadedAgentIds) {

        static AgentPluginReloadOutcome success(List<String> jarFiles, List<String> loadedAgentIds) {
            return new AgentPluginReloadOutcome(true, null, jarFiles, loadedAgentIds);
        }

        static AgentPluginReloadOutcome failure(List<String> jarFiles,
                                                List<String> loadedAgentIds, String message) {
            return new AgentPluginReloadOutcome(false, message, jarFiles, loadedAgentIds);
        }
    }

    /**
     * 插件 agentId 与内置 Agent 冲突。
     */
    public static class AgentPluginConflictException extends Exception {
        public AgentPluginConflictException(String message) {
            super(message);
        }
    }
}
