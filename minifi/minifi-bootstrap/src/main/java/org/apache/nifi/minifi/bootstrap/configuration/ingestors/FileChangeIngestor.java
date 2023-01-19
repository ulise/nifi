/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.minifi.bootstrap.configuration.ingestors;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.util.Collections.emptyList;
import static org.apache.nifi.minifi.bootstrap.configuration.ConfigurationChangeCoordinator.NOTIFIER_INGESTORS_KEY;
import static org.apache.nifi.minifi.bootstrap.configuration.differentiators.WholeConfigDifferentiator.WHOLE_CONFIG_KEY;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.minifi.bootstrap.ConfigurationFileHolder;
import org.apache.nifi.minifi.bootstrap.configuration.ConfigurationChangeNotifier;
import org.apache.nifi.minifi.bootstrap.configuration.differentiators.Differentiator;
import org.apache.nifi.minifi.bootstrap.configuration.differentiators.WholeConfigDifferentiator;
import org.apache.nifi.minifi.bootstrap.configuration.ingestors.interfaces.ChangeIngestor;
import org.apache.nifi.minifi.bootstrap.util.ConfigTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FileChangeIngestor provides a simple FileSystem monitor for detecting changes for a specified file as generated from its corresponding {@link Path}.  Upon modifications to the associated file,
 * associated listeners receive notification of a change allowing configuration logic to be reanalyzed.  The backing implementation is associated with a {@link ScheduledExecutorService} that
 * ensures continuity of monitoring.
 */
public class FileChangeIngestor implements Runnable, ChangeIngestor {

    private static final Map<String, Supplier<Differentiator<ByteBuffer>>> DIFFERENTIATOR_CONSTRUCTOR_MAP;

    static {
        HashMap<String, Supplier<Differentiator<ByteBuffer>>> tempMap = new HashMap<>();
        tempMap.put(WHOLE_CONFIG_KEY, WholeConfigDifferentiator::getByteBufferDifferentiator);

        DIFFERENTIATOR_CONSTRUCTOR_MAP = Collections.unmodifiableMap(tempMap);
    }


    protected static final int DEFAULT_POLLING_PERIOD_INTERVAL = 15;
    protected static final TimeUnit DEFAULT_POLLING_PERIOD_UNIT = TimeUnit.SECONDS;

    private final static Logger logger = LoggerFactory.getLogger(FileChangeIngestor.class);
    private static final String CONFIG_FILE_BASE_KEY = NOTIFIER_INGESTORS_KEY + ".file";

    protected static final String CONFIG_FILE_PATH_KEY = CONFIG_FILE_BASE_KEY + ".config.path";
    protected static final String POLLING_PERIOD_INTERVAL_KEY = CONFIG_FILE_BASE_KEY + ".polling.period.seconds";
    public static final String DIFFERENTIATOR_KEY = CONFIG_FILE_BASE_KEY + ".differentiator";

    private Path configFilePath;
    private WatchService watchService;
    private long pollingSeconds;
    private volatile Differentiator<ByteBuffer> differentiator;
    private volatile ConfigurationChangeNotifier configurationChangeNotifier;
    private volatile ConfigurationFileHolder configurationFileHolder;
    private volatile Properties properties;
    private ScheduledExecutorService executorService;

    protected static WatchService initializeWatcher(Path filePath) {
        try {
            final WatchService fsWatcher = FileSystems.getDefault().newWatchService();
            final Path watchDirectory = filePath.getParent();
            watchDirectory.register(fsWatcher, ENTRY_MODIFY);

            return fsWatcher;
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to initialize a file system watcher for the path " + filePath, ioe);
        }
    }

    protected boolean targetChanged() {
        boolean targetChanged;

        Optional<WatchKey> watchKey = Optional.ofNullable(watchService.poll());

        targetChanged = watchKey
            .map(WatchKey::pollEvents)
            .orElse(emptyList())
            .stream()
            .anyMatch(watchEvent -> ENTRY_MODIFY == watchEvent.kind()
                && ((WatchEvent<Path>) watchEvent).context().equals(configFilePath.getName(configFilePath.getNameCount() - 1)));

        // After completing inspection, reset for detection of subsequent change events
        watchKey.map(WatchKey::reset)
            .filter(valid -> !valid)
            .ifPresent(valid -> {
                throw new IllegalStateException("Unable to reinitialize file system watcher.");
            });

        return targetChanged;
    }

    @Override
    public void run() {
        logger.debug("Checking for a change");
        if (targetChanged()) {
            logger.debug("Target changed, checking if it's different than current flow.");
            try (FileInputStream configFile = new FileInputStream(configFilePath.toFile())) {
                ByteBuffer readOnlyNewConfig =
                    ConfigTransformer.overrideNonFlowSectionsFromOriginalSchema(
                        IOUtils.toByteArray(configFile), configurationFileHolder.getConfigFileReference().get().duplicate(), properties);

                if (differentiator.isNew(readOnlyNewConfig)) {
                    logger.debug("New change, notifying listener");
                    configurationChangeNotifier.notifyListeners(readOnlyNewConfig);
                    logger.debug("Listeners notified");
                }
            } catch (Exception e) {
                logger.error("Could not successfully notify listeners.", e);
            }
        }
    }

    @Override
    public void initialize(Properties properties, ConfigurationFileHolder configurationFileHolder, ConfigurationChangeNotifier configurationChangeNotifier) {
        this.properties = properties;
        this.configurationFileHolder = configurationFileHolder;
        final String rawPath = properties.getProperty(CONFIG_FILE_PATH_KEY);
        final String rawPollingDuration = properties.getProperty(POLLING_PERIOD_INTERVAL_KEY, Long.toString(DEFAULT_POLLING_PERIOD_INTERVAL));

        if (rawPath == null || rawPath.isEmpty()) {
            throw new IllegalArgumentException("Property, " + CONFIG_FILE_PATH_KEY + ", for the path of the config file must be specified.");
        }

        try {
            setConfigFilePath(Paths.get(rawPath));
            setPollingPeriod(Long.parseLong(rawPollingDuration), DEFAULT_POLLING_PERIOD_UNIT);
            setWatchService(initializeWatcher(configFilePath));
        } catch (Exception e) {
            throw new IllegalStateException("Could not successfully initialize file change notifier.", e);
        }

        this.configurationChangeNotifier = configurationChangeNotifier;

        final String differentiatorName = properties.getProperty(DIFFERENTIATOR_KEY);

        if (differentiatorName != null && !differentiatorName.isEmpty()) {
            Supplier<Differentiator<ByteBuffer>> differentiatorSupplier = DIFFERENTIATOR_CONSTRUCTOR_MAP.get(differentiatorName);
            if (differentiatorSupplier == null) {
                throw new IllegalArgumentException("Property, " + DIFFERENTIATOR_KEY + ", has value " + differentiatorName + " which does not " +
                        "correspond to any in the PullHttpChangeIngestor Map:" + DIFFERENTIATOR_CONSTRUCTOR_MAP.keySet());
            }
            differentiator = differentiatorSupplier.get();
        } else {
            differentiator = WholeConfigDifferentiator.getByteBufferDifferentiator();
        }
        differentiator.initialize(configurationFileHolder);
    }

    protected void setConfigFilePath(Path configFilePath) {
        this.configFilePath = configFilePath;
    }

    protected void setWatchService(WatchService watchService) {
        this.watchService = watchService;
    }

    protected void setConfigurationChangeNotifier(ConfigurationChangeNotifier configurationChangeNotifier) {
        this.configurationChangeNotifier = configurationChangeNotifier;
    }

    protected void setDifferentiator(Differentiator<ByteBuffer> differentiator) {
        this.differentiator = differentiator;
    }

    protected void setPollingPeriod(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("Cannot specify a polling period with duration <=0");
        }
        this.pollingSeconds = TimeUnit.SECONDS.convert(duration, unit);
    }

    @Override
    public void start() {
        executorService = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("File Change Notifier Thread");
            t.setDaemon(true);
            return t;
        });
        this.executorService.scheduleWithFixedDelay(this, 0, pollingSeconds, DEFAULT_POLLING_PERIOD_UNIT);
    }

    @Override
    public void close() {
        if (this.executorService != null) {
            this.executorService.shutdownNow();
        }
    }
}

