package org.csanchez.jenkins.plugins.kubernetes;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.ext.RuntimeDelegate;

import jenkins.model.Jenkins;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.github.kubernetes.java.client.exceptions.KubernetesClientException;
import com.github.kubernetes.java.client.interfaces.KubernetesAPIClientInterface;
import com.github.kubernetes.java.client.model.Container;
import com.github.kubernetes.java.client.model.Manifest;
import com.github.kubernetes.java.client.model.Pod;
import com.github.kubernetes.java.client.model.Port;
import com.github.kubernetes.java.client.model.ReplicationController;
import com.github.kubernetes.java.client.model.Selector;
import com.github.kubernetes.java.client.model.State;
import com.github.kubernetes.java.client.model.StateInfo;
import com.github.kubernetes.java.client.v2.KubernetesApiClient;
import com.github.kubernetes.java.client.v2.RestFactory;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.nirima.jenkins.plugins.docker.DockerTemplate;

/**
 * Kubernetes cloud provider.
 * 
 * Starts slaves in a Kubernetes cluster using defined Docker templates for each
 * label.
 * 
 * @author Carlos Sanchez <carlos@apache.org>
 */
public class KubernetesCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(KubernetesCloud.class.getName());
    private static final Pattern SPLIT_IN_SPACES = Pattern.compile("([^\"]\\S*|\".+?\")\\s*");

    public static final String CLOUD_ID_PREFIX = "kubernetes-";

    private static final Random RAND = new Random();

    private static final String DEFAULT_ID = "jenkins-slave";
    private static final String CONTAINER_NAME = "slave";

    public final List<DockerTemplate> templates;
    public final String serverUrl;
    public final String username;
    public final String password;
    public final int containerCap;

    private transient KubernetesAPIClientInterface connection;

    @DataBoundConstructor
    public KubernetesCloud(String name, List<? extends DockerTemplate> templates, String serverUrl, String username,
            String password, String containerCapStr, int connectTimeout, int readTimeout) {
        super(name);

        Preconditions.checkNotNull(serverUrl);

        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        if (templates != null)
            this.templates = new ArrayList<DockerTemplate>(templates);
        else
            this.templates = new ArrayList<DockerTemplate>();

        if (containerCapStr.equals("")) {
            this.containerCap = Integer.MAX_VALUE;
        } else {
            this.containerCap = Integer.parseInt(containerCapStr);
        }
    }

    public String getContainerCapStr() {
        if (containerCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(containerCap);
        }
    }

    /**
     * Connects to Docker.
     *
     * @return Docker client.
     */
    public KubernetesAPIClientInterface connect() {

        LOGGER.log(Level.FINE, "Building connection to Kubernetes host " + name + " URL " + serverUrl);

        if (connection == null) {
            synchronized (this) {
                if (connection != null)
                    return connection;

                RestFactory factory = new RestFactory(getClass().getClassLoader());
                // we need the RestEasy implementation, not the Jersey one
                // loaded by default from the thread classloader
                RuntimeDelegate.setInstance(new ResteasyProviderFactory());
                connection = new KubernetesApiClient(serverUrl.toString(), username, password, factory);
            }
        }
        return connection;

    }

    private String getIdForLabel(Label label) {
        if (label == null) {
            return DEFAULT_ID;
        }
        return "jenkins-" + label.getName();
    }

    @Deprecated
    /**
     * Not using replication controllers for now
     */
    private ReplicationController getOrCreateReplicationController(Label label) throws KubernetesClientException {
        ReplicationController replicationController = null;
        String id = getIdForLabel(label);
        try {
            replicationController = connect().getReplicationController(id);
        } catch (KubernetesClientException e) {
            // probably not found
        }
        if (replicationController == null) {
            LOGGER.log(Level.INFO, "Creating Replication Controller: {0}", id);

            com.github.kubernetes.java.client.model.Label labels = new com.github.kubernetes.java.client.model.Label(id);
            replicationController = new ReplicationController();
            replicationController.setId(id);
            replicationController.setLabels(labels);

            // Desired State
            State state = new State();
            state.setReplicas(0);
            state.setReplicaSelector(new Selector(id));

            // Pod
            state.setPodTemplate(getPodTemplate(label));

            replicationController.setDesiredState(state);
            connect().createReplicationController(replicationController);
            LOGGER.log(Level.INFO, "Created Replication Controller: {0}", id);
        } else {
            // TODO update replication controller if there were changes in
            // Jenkins
        }
        return replicationController;
    }

    private Pod getPodTemplate(Label label) {
        DockerTemplate template = getTemplate(label);
        String id = getIdForLabel(label);
        Pod podTemplate = new Pod();
        podTemplate.setLabels(new com.github.kubernetes.java.client.model.Label(id));
        Container container = new Container();
        container.setName(CONTAINER_NAME);
        container.setImage(template.image);
        // open ssh in a dynamic port, hopefully not yet used host port
        // 49152-65535
        container.setPorts(new Port(22, RAND.nextInt((65535 - 49152) + 1) + 49152));
        container.setCommand(parseDockerCommand(template.dockerCommand));
        Manifest manifest = new Manifest(Collections.singletonList(container), null);
        podTemplate.setDesiredState(new State(manifest));
        return podTemplate;
    }

    /**
     * Split a command in the parts that Docker need
     * 
     * @param dockerCommand
     * @return
     */
    List<String> parseDockerCommand(String dockerCommand) {
        if (dockerCommand == null || dockerCommand.isEmpty()) {
            return null;
        }
        // handle quoted arguments
        Matcher m = SPLIT_IN_SPACES.matcher(dockerCommand);
        List<String> commands = new ArrayList<String>();
        while (m.find()) {
            commands.add(m.group(1).replace("\"", ""));
        }
        return commands;
    }

    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(final Label label, final int excessWorkload) {
        try {

            LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);

            List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

            final DockerTemplate t = getTemplate(label);
            final KubernetesCloud cloud = this;

            for (int i = 1; i <= excessWorkload; i++) {
                r.add(new NodeProvisioner.PlannedNode(t.getDisplayName(), Computer.threadPoolForRemoting
                        .submit(new Callable<Node>() {
                            public Node call() throws Exception {

                                KubernetesSlave slave = null;
                                try {

                                    Pod pod = connect().createPod(getPodTemplate(label));
                                    LOGGER.log(Level.INFO, "Created Pod: {0}", pod);
                                    int j = 600;
                                    for (int i = 0; i < j; i++) {
                                        LOGGER.log(Level.INFO, "Waiting for Pod to be ready ({1}/{2}): {0}",
                                                new Object[] { pod.getId(), i, j });
                                        Thread.sleep(1000);
                                        pod = connect().getPod(pod.getId());
                                        StateInfo info = pod.getCurrentState().getInfo(CONTAINER_NAME);
                                        if ((info != null) && (info.getState("waiting") != null)) {
                                            throw new IllegalStateException("Pod is waiting due to "
                                                    + info.getState("waiting"));
                                        }
                                        if ("Running".equals(pod.getCurrentState().getStatus())) {
                                            break;
                                        }
                                    }
                                    if (!"Running".equals(pod.getCurrentState().getStatus())) {
                                        throw new IllegalStateException("Container is not running after " + j
                                                + " attempts.");
                                    }

                                    slave = new KubernetesSlave(pod, t, getIdForLabel(label), cloud);
                                    Jenkins.getInstance().addNode(slave);
                                    // Docker instances may have a long init
                                    // script. If we declare
                                    // the provisioning complete by returning
                                    // without the connect
                                    // operation, NodeProvisioner may decide
                                    // that it still wants
                                    // one more instance, because it sees that
                                    // (1) all the slaves
                                    // are offline (because it's still being
                                    // launched) and
                                    // (2) there's no capacity provisioned yet.
                                    //
                                    // deferring the completion of provisioning
                                    // until the launch
                                    // goes successful prevents this problem.
                                    slave.toComputer().connect(false).get();
                                    return slave;
                                } catch (Exception ex) {
                                    LOGGER.log(Level.SEVERE, "Error in provisioning; slave={0}, template={1}",
                                            new Object[] { slave, t });

                                    ex.printStackTrace();
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }), t.getNumExecutors()));
            }
            return r;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to count the # of live instances on Kubernetes", e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    public DockerTemplate getTemplate(String template) {
        for (DockerTemplate t : templates) {
            if (t.image.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link DockerTemplate} that has the matching {@link Label}.
     */
    public DockerTemplate getTemplate(Label label) {
        for (DockerTemplate t : templates) {
            if (label == null || label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Add a new template to the cloud
     */
    public void addTemplate(DockerTemplate t) {
        this.templates.add(t);
        // t.parent = this;
    }

    /**
     * Remove a
     * 
     * @param t
     */
    public void removeTemplate(DockerTemplate t) {
        this.templates.remove(t);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Kubernetes";
        }

        public FormValidation doTestConnection(@QueryParameter URL serverUrl, @QueryParameter String username,
                @QueryParameter String password) throws KubernetesClientException, URISyntaxException {

            RestFactory factory = new RestFactory(KubernetesCloud.class.getClassLoader());
            KubernetesAPIClientInterface client = new KubernetesApiClient(serverUrl.toString() + "/api/v1beta1/",
                    username, password, factory);
            client.getAllPods();

            return FormValidation.ok("Connection successful");
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", name).add("serverUrl", serverUrl).toString();
    }
}
