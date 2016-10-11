package io.fabric8.spring.cloud.kubernetes.reload;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.spring.cloud.kubernetes.config.ConfigMapPropertySource;
import io.fabric8.spring.cloud.kubernetes.config.ConfigMapPropertySourceLocator;
import io.fabric8.spring.cloud.kubernetes.config.SecretsPropertySource;
import io.fabric8.spring.cloud.kubernetes.config.SecretsPropertySourceLocator;

import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * A change detector that subscribes to changes in secrets and configmaps and fire a reload when something changes.
 */
public class EventBasedConfigurationChangeDetector extends ConfigurationChangeDetector {

    private ConfigMapPropertySourceLocator configMapPropertySourceLocator;

    private SecretsPropertySourceLocator secretsPropertySourceLocator;

    private Map<String, Watch> watches;

    public EventBasedConfigurationChangeDetector(AbstractEnvironment environment,
                                                 ConfigReloadProperties properties,
                                                 KubernetesClient kubernetesClient,
                                                 ConfigurationUpdateStrategy strategy,
                                                 ConfigMapPropertySourceLocator configMapPropertySourceLocator,
                                                 SecretsPropertySourceLocator secretsPropertySourceLocator) {
        super(environment, properties, kubernetesClient, strategy);

        this.configMapPropertySourceLocator = configMapPropertySourceLocator;
        this.secretsPropertySourceLocator = secretsPropertySourceLocator;
        this.watches = new HashMap<>();
    }

    @PostConstruct
    public void watch() {

        if (properties.isMonitoringConfigMaps()) {
            String name = "config-maps-watch";
            watches.put(name, kubernetesClient.configMaps()
                    .watch(new Watcher<ConfigMap>() {
                        @Override
                        public void eventReceived(Action action, ConfigMap configMap) {
                            onEvent(configMap);
                        }

                        @Override
                        public void onClose(KubernetesClientException e) {
                        }
                    }));
            log.info("Added new Kubernetes watch: {}", name);
        }

        if (properties.isMonitoringSecrets()) {
            String name = "secrets-watch";
            watches.put(name, kubernetesClient.secrets()
                    .watch(new Watcher<Secret>() {
                        @Override
                        public void eventReceived(Action action, Secret secret) {
                            onEvent(secret);
                        }

                        @Override
                        public void onClose(KubernetesClientException e) {
                        }
                    }));
            log.info("Added new Kubernetes watch: {}", name);
        }

        log.info("Kubernetes polling configuration change detector activated");
    }

    @PreDestroy
    public void unwatch() {
        if (this.watches != null) {
            for (Map.Entry<String, Watch> entry : this.watches.entrySet()) {
                try {
                    log.debug("Closing the watch {}", entry.getKey());
                    entry.getValue().close();

                } catch (Exception e) {
                    log.error("Error while closing the watch connection", e);
                }
            }
        }
    }

    private void onEvent(ConfigMap configMap) {
        MapPropertySource currentConfigMapSource = findPropertySource(ConfigMapPropertySource.class);
        if (currentConfigMapSource != null) {
            MapPropertySource newConfigMapSource = configMapPropertySourceLocator.locate(environment);
            if (changed(currentConfigMapSource, newConfigMapSource)) {
                log.info("Detected change in config maps");
                reloadProperties();
            }
        }
    }

    private void onEvent(Secret secret) {
        MapPropertySource currentSecretSource = findPropertySource(SecretsPropertySource.class);
        if (currentSecretSource != null) {
            MapPropertySource newSecretSource = secretsPropertySourceLocator.locate(environment);
            if (changed(currentSecretSource, newSecretSource)) {
                log.info("Detected change in secrets");
                reloadProperties();
            }
        }
    }


}
