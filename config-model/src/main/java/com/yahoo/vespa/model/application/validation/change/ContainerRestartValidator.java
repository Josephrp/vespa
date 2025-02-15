// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation.change;

import com.yahoo.config.model.api.ConfigChangeAction;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.container.QrConfig;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.container.ApplicationContainer;
import com.yahoo.vespa.model.container.Container;
import com.yahoo.vespa.model.container.ContainerCluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toUnmodifiableSet;

/**
 * Returns a restart action for each container that has turned on {@link QrConfig#restartOnDeploy()}.
 *
 * @author bjorncs
 */
public class ContainerRestartValidator implements ChangeValidator {

    @Override
    public List<ConfigChangeAction> validate(VespaModel currentModel, VespaModel nextModel, DeployState deployState) {
        boolean nodesUnchanged = currentModel.allocatedHosts().equals(nextModel.allocatedHosts());
        boolean contentUnchanged = contentHostsOf(currentModel).equals(contentHostsOf(nextModel));
        List<ConfigChangeAction> actions = new ArrayList<>();
        for (ContainerCluster<ApplicationContainer> cluster : nextModel.getContainerClusters().values()) {
            actions.addAll(cluster.getContainers().stream()
                                  .filter(container -> isExistingContainer(container, currentModel))
                                  .filter(container -> shouldContainerRestartOnDeploy(container, nextModel))
                                  .map(container -> createConfigChangeAction(cluster.id(), container, nextModel, nodesUnchanged, contentUnchanged))
                                  .toList());
        }
        return actions;
    }

    private Set<HostSpec> contentHostsOf(VespaModel model) {
        return model.allocatedHosts().getHosts().stream()
                    .filter(spec -> spec.membership()
                                        .map(membership -> membership.cluster().type().isContent())
                                        .orElse(false))
                    .collect(toUnmodifiableSet());
    }

    private static ConfigChangeAction createConfigChangeAction(ClusterSpec.Id id, Container container, VespaModel model,
                                                               boolean nodesUnchanged, boolean contentUnchanged) {
        boolean ignoreOnRedeploy = switch (model.getConfig(QrConfig.class, container.getConfigId()).restartOnInternalRedeploy()) {
            case never -> true;
            case content_changes -> contentUnchanged;
            case node_changes -> nodesUnchanged;
            case always -> false;
        };
        return new VespaRestartAction(id, createMessage(container), container.getServiceInfo(), ignoreOnRedeploy);
    }

    private static String createMessage(Container container) {
        return String.format("Container '%s' is configured to always restart on deploy.", container.getConfigId());
    }

    private static boolean shouldContainerRestartOnDeploy(Container container, VespaModel nextModel) {
        QrConfig config = nextModel.getConfig(QrConfig.class, container.getConfigId());
        return config.restartOnDeploy();
    }

    private static boolean isExistingContainer(Container container, VespaModel currentModel) {
        return currentModel.getService(container.getConfigId()).isPresent();
    }

}
