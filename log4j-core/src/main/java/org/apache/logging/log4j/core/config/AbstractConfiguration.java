/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.async.AsyncLoggerConfig;
import org.apache.logging.log4j.core.async.AsyncLoggerContextSelector;
import org.apache.logging.log4j.core.config.plugins.PluginAliases;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.config.plugins.PluginManager;
import org.apache.logging.log4j.core.config.plugins.PluginNode;
import org.apache.logging.log4j.core.config.plugins.PluginType;
import org.apache.logging.log4j.core.config.plugins.PluginValue;
import org.apache.logging.log4j.core.filter.AbstractFilterable;
import org.apache.logging.log4j.core.helpers.Constants;
import org.apache.logging.log4j.core.helpers.NameUtil;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.lookup.Interpolator;
import org.apache.logging.log4j.core.lookup.MapLookup;
import org.apache.logging.log4j.core.lookup.StrLookup;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.apache.logging.log4j.core.net.Advertiser;
import org.apache.logging.log4j.core.selector.ContextSelector;
import org.apache.logging.log4j.spi.LoggerContextFactory;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.PropertiesUtil;

/**
 * The base Configuration. Many configuration implementations will extend this class.
 */
public abstract class AbstractConfiguration extends AbstractFilterable implements Configuration {

    /**
     * Allow subclasses access to the status logger without creating another instance.
     */
    protected static final Logger LOGGER = StatusLogger.getLogger();
    private static final int BUF_SIZE = 16384;

    /**
     * The root node of the configuration.
     */
    protected Node rootNode;

    /**
     * Listeners for configuration changes.
     */
    protected final List<ConfigurationListener> listeners = new CopyOnWriteArrayList<ConfigurationListener>();

    /**
     * The ConfigurationMonitor that checks for configuration changes.
     */
    protected ConfigurationMonitor monitor = new DefaultConfigurationMonitor();

    /**
     * The Advertiser which exposes appender configurations to external systems.
     */
    private Advertiser advertiser = new DefaultAdvertiser();

    protected Map<String, String> advertisedConfiguration;

    private Node advertiserNode = null;

    private Object advertisement;

    /**
     *
     */
    protected boolean isShutdownHookEnabled = true;

    private String name;

    private ConcurrentMap<String, Appender> appenders = new ConcurrentHashMap<String, Appender>();

    private ConcurrentMap<String, LoggerConfig> loggers = new ConcurrentHashMap<String, LoggerConfig>();

    private final ConcurrentMap<String, String> properties = new ConcurrentHashMap<String, String>();

    private final StrLookup tempLookup = new Interpolator(properties);

    private final StrSubstitutor subst = new StrSubstitutor(tempLookup);

    private LoggerConfig root = new LoggerConfig();

    private final boolean started = false;

    private final ConcurrentMap<String, Object> componentMap = new ConcurrentHashMap<String, Object>();

    protected PluginManager pluginManager;

    /**
     * Constructor.
     */
    protected AbstractConfiguration() {
        componentMap.put(Configuration.CONTEXT_PROPERTIES, properties);
        pluginManager = new PluginManager("Core");
        rootNode = new Node();
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Initialize the configuration.
     */
    @Override
    public void start() {
        pluginManager.collectPlugins();
        PluginManager levelPlugins = new PluginManager("Level");
        levelPlugins.collectPlugins();
        Map<String, PluginType<?>> plugins = levelPlugins.getPlugins();
        if (plugins != null) {
            for (PluginType<?> type : plugins.values()) {
                try {
                    // Cause the class to be initialized if it isn't already.
                    Class.forName(type.getPluginClass().getName(), true, type.getPluginClass().getClassLoader());
                } catch (Exception ex) {
                    LOGGER.error("Unable to initialize {} due to {}: {}", type.getPluginClass().getName(),
                            ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
        }
        setup();
        setupAdvertisement();
        doConfigure();
        for (final LoggerConfig logger : loggers.values()) {
            logger.startFilter();
        }
        for (final Appender appender : appenders.values()) {
            appender.start();
        }
        root.startFilter(); // LOG4J2-336
        startFilter();
    }

    /**
     * Tear down the configuration.
     */
    @Override
    public void stop() {

        // LOG4J2-392 first stop AsyncLogger Disruptor thread
        final LoggerContextFactory factory = LogManager.getFactory();
        if (factory instanceof Log4jContextFactory) {
            ContextSelector selector = ((Log4jContextFactory) factory).getSelector();
            if (selector instanceof AsyncLoggerContextSelector) { // all loggers are async
                // TODO until LOG4J2-493 is fixed we can only stop AsyncLogger once!
                // but LoggerContext.setConfiguration will call config.stop()
                // every time the configuration changes...
                //
                // Uncomment the line below after LOG4J2-493 is fixed
                //AsyncLogger.stop();
            }
        }
        // similarly, first stop AsyncLoggerConfig Disruptor thread(s)
        Set<LoggerConfig> alreadyStopped = new HashSet<LoggerConfig>();
        for (final LoggerConfig logger : loggers.values()) {
            if (logger instanceof AsyncLoggerConfig) {
                logger.clearAppenders();
                logger.stopFilter();
                alreadyStopped.add(logger);
            }
        }
        if (root instanceof AsyncLoggerConfig) {
            root.stopFilter();
            alreadyStopped.add(root);
        }

        // Stop the appenders in reverse order in case they still have activity.
        final Appender[] array = appenders.values().toArray(new Appender[appenders.size()]);

        // LOG4J2-511, LOG4J2-392 stop AsyncAppenders first
        for (int i = array.length - 1; i >= 0; --i) {
            if (array[i] instanceof AsyncAppender) {
                array[i].stop();
            }
        }
        for (int i = array.length - 1; i >= 0; --i) {
            if (array[i].isStarted()) { // then stop remaining Appenders
                array[i].stop();
            }
        }
        for (final LoggerConfig logger : loggers.values()) {
            if (alreadyStopped.contains(logger)) {
                continue;
            }
            logger.clearAppenders();
            logger.stopFilter();
        }
        if (!alreadyStopped.contains(root)) {
            root.stopFilter();
        }
        stopFilter();
        if (advertiser != null && advertisement != null) {
            advertiser.unadvertise(advertisement);
        }
    }

    @Override
    public boolean isShutdownHookEnabled() {
        return isShutdownHookEnabled;
    }

    protected void setup() {
    }

    protected Level getDefaultStatus() {
        final String statusLevel = PropertiesUtil.getProperties().getStringProperty(Constants.LOG4J_DEFAULT_STATUS_LEVEL,
            Level.ERROR.name());
        try {
            return Level.toLevel(statusLevel);
        } catch (final Exception ex) {
            return Level.ERROR;
        }
    }

    protected void createAdvertiser(String advertiserString, ConfigurationFactory.ConfigurationSource configSource,
                                    byte[] buffer, String contentType) {
        if (advertiserString != null) {
            Node node = new Node(null, advertiserString, null);
            Map<String, String> attributes = node.getAttributes();
            attributes.put("content", new String(buffer));
            attributes.put("contentType", contentType);
            attributes.put("name", "configuration");
            if (configSource.getLocation() != null) {
                attributes.put("location", configSource.getLocation());
            }
            advertiserNode = node;
        }
    }

    private void setupAdvertisement() {
        if (advertiserNode != null)
        {
            String name = advertiserNode.getName();
            @SuppressWarnings("unchecked")
            final PluginType<Advertiser> type = (PluginType<Advertiser>) pluginManager.getPluginType(name);
            if (type != null)
            {
                final Class<Advertiser> clazz = type.getPluginClass();
                try {
                    advertiser = clazz.newInstance();
                    advertisement = advertiser.advertise(advertiserNode.getAttributes());
                } catch (final InstantiationException e) {
                    LOGGER.error("InstantiationException attempting to instantiate advertiser: {}", name, e);
                } catch (final IllegalAccessException e) {
                    LOGGER.error("IllegalAccessException attempting to instantiate advertiser: {}", name, e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getComponent(final String name) {
        return (T) componentMap.get(name);
    }

    @Override
    public void addComponent(final String name, final Object obj) {
        componentMap.putIfAbsent(name, obj);
    }

    @SuppressWarnings("unchecked")
    protected void doConfigure() {
        boolean setRoot = false;
        boolean setLoggers = false;
        if (rootNode.hasChildren() && rootNode.getChildren().get(0).getName().equalsIgnoreCase("Properties")) {
            Node first = rootNode.getChildren().get(0);
            createConfiguration(first, null);
            if (first.getObject() != null) {
                subst.setVariableResolver((StrLookup) first.getObject());
            }
        } else {
            final Map<String, String> map = (Map<String, String>) componentMap.get(CONTEXT_PROPERTIES);
            final StrLookup lookup = map == null ? null : new MapLookup(map);
            subst.setVariableResolver(new Interpolator(lookup));
        }

        for (final Node child : rootNode.getChildren()) {
            if (child.getName().equalsIgnoreCase("Properties")) {
                if (tempLookup == subst.getVariableResolver()) {
                    LOGGER.error("Properties declaration must be the first element in the configuration");
                }
                continue;
            }
            createConfiguration(child, null);
            if (child.getObject() == null) {
                continue;
            }
            if (child.getName().equalsIgnoreCase("Appenders")) {
                appenders = (ConcurrentMap<String, Appender>) child.getObject();
            } else if (child.getObject() instanceof Filter) {
                addFilter((Filter) child.getObject());
            } else if (child.getName().equalsIgnoreCase("Loggers")) {
                final Loggers l = (Loggers) child.getObject();
                loggers = l.getMap();
                setLoggers = true;
                if (l.getRoot() != null) {
                    root = l.getRoot();
                    setRoot = true;
                }
            } else {
                LOGGER.error("Unknown object \"{}\" of type {} is ignored.", child.getName(),
                        child.getObject().getClass().getName());
            }
        }

        if (!setLoggers) {
            LOGGER.warn("No Loggers were configured, using default. Is the Loggers element missing?");
            setToDefault();
            return;
        } else if (!setRoot) {
            LOGGER.warn("No Root logger was configured, creating default ERROR-level Root logger with Console appender");
            setToDefault();
            // return; // LOG4J2-219: creating default root=ok, but don't exclude configured Loggers
        }

        for (final Map.Entry<String, LoggerConfig> entry : loggers.entrySet()) {
            final LoggerConfig l = entry.getValue();
            for (final AppenderRef ref : l.getAppenderRefs()) {
                final Appender app = appenders.get(ref.getRef());
                if (app != null) {
                    l.addAppender(app, ref.getLevel(), ref.getFilter());
                } else {
                    LOGGER.error("Unable to locate appender {} for logger {}", ref.getRef(), l.getName());
                }
            }

        }

        setParents();
    }

    private void setToDefault() {
        setName(DefaultConfiguration.DEFAULT_NAME);
        final Layout<? extends Serializable> layout =
                PatternLayout.createLayout("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n",
                        null, null, null, null, null, null, null);
        final Appender appender = ConsoleAppender.createAppender(layout, null, "SYSTEM_OUT", "Console", "false",
            "true");
        appender.start();
        addAppender(appender);
        final LoggerConfig root = getRootLogger();
        root.addAppender(appender, null, null);

        final String levelName = PropertiesUtil.getProperties().getStringProperty(DefaultConfiguration.DEFAULT_LEVEL);
        final Level level = levelName != null && Level.getLevel(levelName) != null ?
            Level.getLevel(levelName) : Level.ERROR;
        root.setLevel(level);
    }

    /**
     * Set the name of the configuration.
     * @param name The name.
     */
    public void setName(final String name) {
        this.name = name;
    }

    /**
     * Returns the name of the configuration.
     * @return the name of the configuration.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Add a listener for changes on the configuration.
     * @param listener The ConfigurationListener to add.
     */
    @Override
    public void addListener(final ConfigurationListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a ConfigurationListener.
     * @param listener The ConfigurationListener to remove.
     */
    @Override
    public void removeListener(final ConfigurationListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns the Appender with the specified name.
     * @param name The name of the Appender.
     * @return the Appender with the specified name or null if the Appender cannot be located.
     */
    public Appender getAppender(final String name) {
        return appenders.get(name);
    }

    /**
     * Returns a Map containing all the Appenders and their name.
     * @return A Map containing each Appender's name and the Appender object.
     */
    @Override
    public Map<String, Appender> getAppenders() {
        return appenders;
    }

    /**
     * Adds an Appender to the configuration.
     * @param appender The Appender to add.
     */
    @Override
    public void addAppender(final Appender appender) {
        appenders.putIfAbsent(appender.getName(), appender);
    }

    @Override
    public StrSubstitutor getStrSubstitutor() {
        return subst;
    }

    @Override
    public void setConfigurationMonitor(final ConfigurationMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public ConfigurationMonitor getConfigurationMonitor() {
        return monitor;
    }

    @Override
    public void setAdvertiser(final Advertiser advertiser) {
        this.advertiser = advertiser;
    }

    @Override
    public Advertiser getAdvertiser() {
        return advertiser;
    }

    /**
     * Associates an Appender with a LoggerConfig. This method is synchronized in case a Logger with the
     * same name is being updated at the same time.
     *
     * Note: This method is not used when configuring via configuration. It is primarily used by
     * unit tests.
     * @param logger The Logger the Appender will be associated with.
     * @param appender The Appender.
     */
    @Override
    public synchronized void addLoggerAppender(final org.apache.logging.log4j.core.Logger logger,
                                               final Appender appender) {
        final String name = logger.getName();
        appenders.putIfAbsent(appender.getName(), appender);
        final LoggerConfig lc = getLoggerConfig(name);
        if (lc.getName().equals(name)) {
            lc.addAppender(appender, null, null);
        } else {
            final LoggerConfig nlc = new LoggerConfig(name, lc.getLevel(), lc.isAdditive());
            nlc.addAppender(appender, null, null);
            nlc.setParent(lc);
            loggers.putIfAbsent(name, nlc);
            setParents();
            logger.getContext().updateLoggers();
        }
    }
    /**
     * Associates a Filter with a LoggerConfig. This method is synchronized in case a Logger with the
     * same name is being updated at the same time.
     *
     * Note: This method is not used when configuring via configuration. It is primarily used by
     * unit tests.
     * @param logger The Logger the Fo;ter will be associated with.
     * @param filter The Filter.
     */
    @Override
    public synchronized void addLoggerFilter(final org.apache.logging.log4j.core.Logger logger, final Filter filter) {
        final String name = logger.getName();
        final LoggerConfig lc = getLoggerConfig(name);
        if (lc.getName().equals(name)) {

            lc.addFilter(filter);
        } else {
            final LoggerConfig nlc = new LoggerConfig(name, lc.getLevel(), lc.isAdditive());
            nlc.addFilter(filter);
            nlc.setParent(lc);
            loggers.putIfAbsent(name, nlc);
            setParents();
            logger.getContext().updateLoggers();
        }
    }
    /**
     * Marks a LoggerConfig as additive. This method is synchronized in case a Logger with the
     * same name is being updated at the same time.
     *
     * Note: This method is not used when configuring via configuration. It is primarily used by
     * unit tests.
     * @param logger The Logger the Appender will be associated with.
     * @param additive True if the LoggerConfig should be additive, false otherwise.
     */
    @Override
    public synchronized void setLoggerAdditive(final org.apache.logging.log4j.core.Logger logger,
                                               final boolean additive) {
        final String name = logger.getName();
        final LoggerConfig lc = getLoggerConfig(name);
        if (lc.getName().equals(name)) {
            lc.setAdditive(additive);
        } else {
            final LoggerConfig nlc = new LoggerConfig(name, lc.getLevel(), additive);
            nlc.setParent(lc);
            loggers.putIfAbsent(name, nlc);
            setParents();
            logger.getContext().updateLoggers();
        }
    }

    /**
     * Remove an Appender. First removes any associations between LoggerConfigs and the Appender, removes
     * the Appender from this appender list and then stops the appender. This method is synchronized in
     * case an Appender with the same name is being added during the removal.
     * @param name the name of the appender to remove.
     */
    public synchronized void removeAppender(final String name) {
        for (final LoggerConfig logger : loggers.values()) {
            logger.removeAppender(name);
        }
        final Appender app = appenders.remove(name);

        if (app != null) {
            app.stop();
        }
    }

    /**
     * Locates the appropriate LoggerConfig for a Logger name. This will remove tokens from the
     * package name as necessary or return the root LoggerConfig if no other matches were found.
     * @param name The Logger name.
     * @return The located LoggerConfig.
     */
    @Override
    public LoggerConfig getLoggerConfig(final String name) {
        if (loggers.containsKey(name)) {
            return loggers.get(name);
        }
        String substr = name;
        while ((substr = NameUtil.getSubName(substr)) != null) {
            if (loggers.containsKey(substr)) {
                return loggers.get(substr);
            }
        }
        return root;
    }

    /**
     * Returns the root Logger.
     * @return the root Logger.
     */
    public LoggerConfig getRootLogger() {
        return root;
    }

    /**
     * Returns a Map of all the LoggerConfigs.
     * @return a Map with each entry containing the name of the Logger and the LoggerConfig.
     */
    @Override
    public Map<String, LoggerConfig> getLoggers() {
        return Collections.unmodifiableMap(loggers);
    }

    /**
     * Returns the LoggerConfig with the specified name.
     * @param name The Logger name.
     * @return The LoggerConfig or null if no match was found.
     */
    public LoggerConfig getLogger(final String name) {
        return loggers.get(name);
    }

    /**
     * Add a loggerConfig. The LoggerConfig must already be configured with Appenders, Filters, etc.
     * After addLogger is called LoggerContext.updateLoggers must be called.
     *
     * @param name The name of the Logger.
     * @param loggerConfig The LoggerConfig.
     */
    @Override
    public synchronized void addLogger(final String name, final LoggerConfig loggerConfig) {
        loggers.putIfAbsent(name, loggerConfig);
        setParents();
    }

    /**
     * Remove a LoggerConfig.
     *
     * @param name The name of the Logger.
     */
    @Override
    public synchronized void removeLogger(final String name) {
        loggers.remove(name);
        setParents();
    }

    @Override
    public void createConfiguration(final Node node, final LogEvent event) {
        final PluginType<?> type = node.getType();
        if (type != null && type.isDeferChildren()) {
            node.setObject(createPluginObject(type, node, event));
        } else {
            for (final Node child : node.getChildren()) {
                createConfiguration(child, event);
            }

            if (type == null) {
                if (node.getParent() != null) {
                    LOGGER.error("Unable to locate plugin for {}", node.getName());
                }
            } else {
                node.setObject(createPluginObject(type, node, event));
            }
        }
    }

   /*
    * Retrieve a static public 'method to create the desired object. Every parameter
    * will be annotated to identify the appropriate attribute or element to use to
    * set the value of the parameter.
    * Parameters annotated with PluginAttribute will always be set as Strings.
    * Parameters annotated with PluginElement may be Objects or arrays. Collections
    * and Maps are currently not supported, although the factory method that is called
    * can create these from an array.
    *
    * Although the happy path works, more work still needs to be done to log incorrect
    * parameters. These will generally result in unhelpful InvocationTargetExceptions.
    * @param classClass the class.
    * @return the instantiate method or null if there is none by that
    * description.
    */
    private <T> Object createPluginObject(final PluginType<T> type, final Node node, final LogEvent event)
    {
        // TODO: add support for type conversion
        final Class<T> clazz = type.getPluginClass();

        if (Map.class.isAssignableFrom(clazz)) {
            try {
                return createPluginMap(node, clazz);
            } catch (final Exception ex) {
                LOGGER.warn("Unable to create Map for {} of class {}", type.getElementName(), clazz);
            }
        }

        if (Collection.class.isAssignableFrom(clazz)) {
            try {
                return createPluginCollection(node, clazz);
            } catch (final Exception ex) {
                LOGGER.warn("Unable to create List for {} of class {}", type.getElementName(), clazz);
            }
        }

        Method factoryMethod = findFactoryMethod(clazz);
        if (factoryMethod == null) return null;

        final Annotation[][] parmArray = factoryMethod.getParameterAnnotations();
        final Class<?>[] parmClasses = factoryMethod.getParameterTypes();
        if (parmArray.length != parmClasses.length) {
            LOGGER.error("Number of parameter annotations ({}) does not equal the number of parameters ({})",
                    parmArray.length, parmClasses.length
            );
        }
        final Object[] parms = new Object[parmClasses.length];

        int index = 0;
        final Map<String, String> attrs = node.getAttributes();
        final List<Node> children = node.getChildren();
        final StringBuilder sb = new StringBuilder();
        final List<Node> used = new ArrayList<Node>();

        /*
         * For each parameter:
         * If the parameter is an attribute store the value of the attribute in the parameter array.
         * If the parameter is an element:
         *   Determine if the required parameter is an array.
         *     If so, if a child contains the array, use it,
         *      otherwise create the array from all child nodes of the correct type.
         *     Store the array into the parameter array.
         *   If not an array, store the object in the child node into the parameter array.
         */
        for (final Annotation[] parmTypes : parmArray) {
            String[] aliases = extractPluginAliases(parmTypes);
            for (final Annotation a : parmTypes) {
                if (a instanceof PluginAliases) {
                    continue;
                }
                if (sb.length() == 0) {
                    sb.append(" with params(");
                } else {
                    sb.append(", ");
                }
                if (a instanceof PluginNode) {
                    parms[index] = node;
                    sb.append("Node=").append(node.getName());
                } else if (a instanceof PluginConfiguration) {
                    parms[index] = this;
                    if (this.name != null) {
                        sb.append("Configuration(").append(name).append(')');
                    } else {
                        sb.append("Configuration");
                    }
                } else if (a instanceof PluginValue) {
                    final String name = ((PluginValue) a).value();
                    String v = node.getValue();
                    if (v == null) {
                        v = getAttrValue("value", null, attrs);
                    }
                    final String value = subst.replace(event, v);
                    sb.append(name).append("=\"").append(value).append('"');
                    parms[index] = value;
                } else if (a instanceof PluginAttribute) {
                    PluginAttribute attr = (PluginAttribute) a;
                    final String name = attr.value();
                    final String value = subst.replace(event, getAttrValue(name, aliases, attrs));
                    sb.append(name).append("=\"").append(value).append('"');
                    parms[index] = value;
                } else if (a instanceof PluginElement) {
                    final PluginElement elem = (PluginElement) a;
                    final String name = elem.value();
                    if (parmClasses[index].isArray()) {
                        final Class<?> parmClass = parmClasses[index].getComponentType();
                        final List<Object> list = new ArrayList<Object>();
                        sb.append(name).append("={");
                        boolean first = true;
                        for (final Node child : children) {
                            final PluginType<?> childType = child.getType();
                            if (name.equalsIgnoreCase(childType.getElementName()) ||
                                    parmClass.isAssignableFrom(childType.getPluginClass())) {
                                used.add(child);
                                if (!first) {
                                    sb.append(", ");
                                }
                                first = false;
                                final Object obj = child.getObject();
                                if (obj == null) {
                                    LOGGER.error("Null object returned for {} in {}", child.getName(), node.getName());
                                    continue;
                                }
                                if (obj.getClass().isArray()) {
                                    printArray(sb, (Object[]) obj);
                                    parms[index] = obj;
                                    break;
                                }
                                sb.append(child.toString());
                                list.add(obj);
                            }
                        }
                        sb.append('}');
                        if (parms[index] != null) {
                            break;
                        }
                        if (!(list.isEmpty() || parmClass.isAssignableFrom(list.get(0).getClass()))) {
                            LOGGER.error(
                                    "Attempted to assign List containing class {} to array of type {} for attribute {}",
                                    list.get(0).getClass().getName(), parmClass, name
                            );
                            break;
                        }
                        parms[index] = collectionToArray(list, parmClass);
                    } else {
                        final Node child = findNamedNode(name, parmClasses[index], children);
                        if (child == null) {
                            sb.append("null");
                        } else {
                            sb.append(child.getName()).append('(').append(child.toString()).append(')');
                            used.add(child);
                            parms[index] = child.getObject();
                        }
                    }
                }
            }
            ++index;
        }
        if (sb.length() > 0) {
            sb.append(')');
        }

        checkForRemainingAttributes(node);

        if (!type.isDeferChildren() && used.size() != children.size()) {
            children.removeAll(used);
            for (final Node child : children) {
                final String nodeType = node.getType().getElementName();
                final String start = nodeType.equals(node.getName()) ? node.getName() : nodeType + ' ' + node.getName();
                LOGGER.error("{} has no parameter that matches element {}", start, child.getName());
            }
        }

        try {
            final int mod = factoryMethod.getModifiers();
            if (!Modifier.isStatic(mod)) {
                LOGGER.error("{} method is not static on class {} for element {}",
                        factoryMethod.getName(), clazz.getName(), node.getName());
                return null;
            }
            LOGGER.debug("Calling {} on class {} for element {}{}", factoryMethod.getName(), clazz.getName(),
                    node.getName(), sb.toString());
            //if (parms.length > 0) {
            return factoryMethod.invoke(null, parms);
            //}
            //return factoryMethod.invoke(null, node);
        } catch (final Exception e) {
            LOGGER.error("Unable to invoke method {} in class {} for element {}",
                    factoryMethod.getName(), clazz.getName(), node.getName(), e);
        }
        return null;
    }

    private static Object[] collectionToArray(final Collection<?> collection, final Class<?> type) {
        final Object[] array = (Object[]) Array.newInstance(type, collection.size());
        int i = 0;
        for (final Object obj : collection) {
            array[i] = obj;
            ++i;
        }
        return array;
    }

    private static Node findNamedNode(final String name, final Class<?> type, final Iterable<Node> nodes) {
        for (final Node child : nodes) {
            final PluginType<?> childType = child.getType();
            if (name.equalsIgnoreCase(childType.getElementName()) ||
                    type.isAssignableFrom(childType.getPluginClass())) {
                return child;
            }
        }
        return null;
    }

    private static void checkForRemainingAttributes(final Node node) {
        final Map<String, String> attrs = node.getAttributes();
        if (!attrs.isEmpty()) {
            final StringBuilder eb = new StringBuilder();
            for (final String key : attrs.keySet()) {
                if (eb.length() == 0) {
                    eb.append(node.getName());
                    eb.append(" contains ");
                    if (attrs.size() == 1) {
                        eb.append("an invalid element or attribute ");
                    } else {
                        eb.append("invalid attributes ");
                    }
                } else {
                    eb.append(", ");
                }
                eb.append('"');
                eb.append(key);
                eb.append('"');

            }
            LOGGER.error(eb.toString());
        }
    }

    private static String[] extractPluginAliases(final Annotation... parmTypes) {
        String[] aliases = null;
        for (final Annotation a : parmTypes) {
            if (a instanceof PluginAliases) {
                aliases = ((PluginAliases) a).value();
            }
        }
        return aliases;
    }

    private static <T> Object createPluginMap(final Node node, final Class<T> clazz) throws InstantiationException, IllegalAccessException {
        @SuppressWarnings("unchecked")
        final Map<String, Object> map = (Map<String, Object>) clazz.newInstance();
        for (final Node child : node.getChildren()) {
            map.put(child.getName(), child.getObject());
        }
        return map;
    }

    private static <T> Object createPluginCollection(final Node node, final Class<T> clazz) throws InstantiationException, IllegalAccessException {
        @SuppressWarnings("unchecked")
        final Collection<Object> list = (Collection<Object>) clazz.newInstance();
        for (final Node child : node.getChildren()) {
            list.add(child.getObject());
        }
        return list;
    }

    private static <T> Method findFactoryMethod(final Class<T> clazz) {
        for (final Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(PluginFactory.class)) {
                return method;
            }
        }
        // TODO: this should probably throw an exception instead of returning null
        return null;
    }

    private void printArray(final StringBuilder sb, final Object... array) {
        boolean first = true;
        for (final Object obj : array) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(obj.toString());
            first = false;
        }
    }

    private String getAttrValue(final String name, final String[] aliases, final Map<String, String> attrs) {
        for (final Map.Entry<String, String> entry : attrs.entrySet()) {
            final String key = entry.getKey();
            if (key.equalsIgnoreCase(name)) {
                final String attr = entry.getValue();
                attrs.remove(key);
                return attr;
            }
            if (aliases != null) {
                for (String alias : aliases) {
                    if (key.equalsIgnoreCase(alias)) {
                        final String attr = entry.getValue();
                        attrs.remove(key);
                        return attr;
                    }
                }
            }
        }
        return null;
    }

    private void setParents() {
         for (final Map.Entry<String, LoggerConfig> entry : loggers.entrySet()) {
            final LoggerConfig logger = entry.getValue();
            String name = entry.getKey();
            if (!name.isEmpty()) {
                final int i = name.lastIndexOf('.');
                if (i > 0) {
                    name = name.substring(0, i);
                    LoggerConfig parent = getLoggerConfig(name);
                    if (parent == null) {
                        parent = root;
                    }
                    logger.setParent(parent);
                } else {
                    logger.setParent(root);
                }
            }
        }
    }

    protected byte[] toByteArray(final InputStream is) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        final byte[] data = new byte[BUF_SIZE];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }
}
