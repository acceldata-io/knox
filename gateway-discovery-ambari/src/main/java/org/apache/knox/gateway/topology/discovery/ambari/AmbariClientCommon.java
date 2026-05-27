/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.knox.gateway.topology.discovery.ambari;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.apache.knox.gateway.config.GatewayConfig;
import org.apache.knox.gateway.i18n.messages.MessagesFactory;
import org.apache.knox.gateway.services.security.AliasService;
import org.apache.knox.gateway.services.security.KeystoreService;
import org.apache.knox.gateway.topology.discovery.ServiceDiscoveryConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

class AmbariClientCommon {

    private static final AmbariServiceDiscoveryMessages log = MessagesFactory.get(AmbariServiceDiscoveryMessages.class);

    static final String AMBARI_CLUSTERS_URI = "/api/v1/clusters";

    static final String AMBARI_HOSTROLES_URI =
                                    AMBARI_CLUSTERS_URI + "/%s/services?fields=components/host_components/HostRoles";

    static final String AMBARI_SERVICES_URI =
                                    AMBARI_CLUSTERS_URI + "/%s/services?fields=ServiceInfo/service_name";

    static final String AMBARI_SERVICECONFIGS_URI =
                                    AMBARI_CLUSTERS_URI + "/%s/configurations/service_config_versions?is_current=true";

    static final String AMBARI_SERVICECONFIGS_BY_SERVICE_URI =
                                    AMBARI_CLUSTERS_URI + "/%s/configurations/service_config_versions?is_current=true&service_name=%s";

    private RESTInvoker restClient;


    AmbariClientCommon(GatewayConfig config, AliasService aliasService, KeystoreService keystoreService) {
        this(new RESTInvoker(config, aliasService, keystoreService));
    }


    AmbariClientCommon(RESTInvoker restInvoker) {
        this.restClient = restInvoker;
    }



    Map<String, Map<String, AmbariCluster.ServiceConfiguration>> getActiveServiceConfigurations(String clusterName,
                                                                                                ServiceDiscoveryConfig config) {
        Map<String, Map<String, AmbariCluster.ServiceConfiguration>> activeConfigs = null;

        if (config != null) {
            activeConfigs = getActiveServiceConfigurations(config.getAddress(),
                                                           clusterName,
                                                           config.getUser(),
                                                           config.getPasswordAlias());
        }

        return activeConfigs;
    }


    Map<String, Map<String, AmbariCluster.ServiceConfiguration>> getActiveServiceConfigurations(String discoveryAddress,
                                                                                                String clusterName,
                                                                                                String discoveryUser,
                                                                                                String discoveryPwdAlias) {
        Map<String, Map<String, AmbariCluster.ServiceConfiguration>> serviceConfigurations = new HashMap<>();

        String serviceConfigsURL = String.format(Locale.ROOT, "%s" + AMBARI_SERVICECONFIGS_URI, discoveryAddress, clusterName);
        JSONObject serviceConfigsJSON = restClient.invoke(serviceConfigsURL, discoveryUser, discoveryPwdAlias);

        if (serviceConfigsJSON != null) {
            processServiceConfigsJSON(serviceConfigsJSON, serviceConfigurations);
        } else {
            // Bulk fetch failed (e.g., a service has a config property with invalid JSON characters).
            // Fall back to per-service requests so that one broken service doesn't block all discovery.
            log.fallbackToPerServiceConfigFetch(clusterName);
            for (String serviceName : getServiceNames(discoveryAddress, clusterName, discoveryUser, discoveryPwdAlias)) {
                String perServiceURL = String.format(Locale.ROOT, "%s" + AMBARI_SERVICECONFIGS_BY_SERVICE_URI,
                                                     discoveryAddress, clusterName, serviceName);
                JSONObject perServiceJSON = restClient.invoke(perServiceURL, discoveryUser, discoveryPwdAlias);
                if (perServiceJSON != null) {
                    processServiceConfigsJSON(perServiceJSON, serviceConfigurations);
                } else {
                    log.skippingServiceConfigDueToParseError(serviceName, clusterName);
                }
            }
        }

        return serviceConfigurations;
    }

    private void processServiceConfigsJSON(JSONObject serviceConfigsJSON,
                                           Map<String, Map<String, AmbariCluster.ServiceConfiguration>> serviceConfigurations) {
        if (serviceConfigsJSON == null) {
            return;
        }

        Object items = serviceConfigsJSON.get("items");
        if (!(items instanceof JSONArray)) {
            return;
        }

        JSONArray serviceConfigs = (JSONArray) items;
        for (Object serviceConfig : serviceConfigs) {
            if (!(serviceConfig instanceof JSONObject)) {
                continue;
            }

            JSONObject serviceConfigJSON = (JSONObject) serviceConfig;
            Object serviceNameValue = serviceConfigJSON.get("service_name");
            if (!(serviceNameValue instanceof String)) {
                continue;
            }
            String serviceName = (String) serviceNameValue;

            Object configurationsValue = serviceConfigJSON.get("configurations");
            if (!(configurationsValue instanceof JSONArray)) {
                continue;
            }

            JSONArray configurations = (JSONArray) configurationsValue;
            for (Object configuration : configurations) {
                if (!(configuration instanceof JSONObject)) {
                    continue;
                }

                JSONObject configurationJSON = (JSONObject) configuration;
                Object configTypeValue = configurationJSON.get("type");
                if (!(configTypeValue instanceof String)) {
                    continue;
                }

                String configType = (String) configTypeValue;
                String configVersion = String.valueOf(configurationJSON.get("version"));

                Map<String, String> configProps = new HashMap<>();
                Object propertiesValue = configurationJSON.get("properties");
                if (propertiesValue instanceof JSONObject) {
                    JSONObject configProperties = (JSONObject) propertiesValue;
                    for (Entry<String, Object> entry : configProperties.entrySet()) {
                        configProps.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }

                serviceConfigurations.computeIfAbsent(serviceName, k -> new HashMap<>())
                                     .put(configType, new AmbariCluster.ServiceConfiguration(configType,
                                                                                             configVersion,
                                                                                             configProps));
            }
        }
    }

    private List<String> getServiceNames(String discoveryAddress, String clusterName,
                                         String discoveryUser, String discoveryPwdAlias) {
        List<String> serviceNames = new ArrayList<>();
        String servicesURL = String.format(Locale.ROOT, "%s" + AMBARI_SERVICES_URI, discoveryAddress, clusterName);
        JSONObject servicesJSON = restClient.invoke(servicesURL, discoveryUser, discoveryPwdAlias);
        if (servicesJSON != null) {
            Object itemsObject = servicesJSON.get("items");
            if (!(itemsObject instanceof JSONArray)) {
                return serviceNames;
            }

            JSONArray items = (JSONArray) itemsObject;
            for (Object item : items) {
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject serviceInfo = (JSONObject) ((JSONObject) item).get("ServiceInfo");
                if (serviceInfo != null) {
                    String name = (String) serviceInfo.get("service_name");
                    if (name != null) {
                        serviceNames.add(name);
                    }
                }
            }
        }
        return serviceNames;
    }


}
