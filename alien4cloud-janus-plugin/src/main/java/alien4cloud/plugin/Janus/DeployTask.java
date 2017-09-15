/*
* Copyright 2016 Bull Atos.  All Rights Reserved.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* See the NOTICE file distributed with this work for additional information
* regarding copyright ownership.
*/
package alien4cloud.plugin.Janus;

import static alien4cloud.paas.wf.util.WorkflowUtils.isOfType;
import static com.google.common.io.Files.copy;

import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.model.deployment.DeploymentTopology;
import alien4cloud.model.orchestrators.locations.Location;
import alien4cloud.paas.IPaaSCallback;
import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.InstanceInformation;
import alien4cloud.paas.model.InstanceStatus;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.plugin.Janus.rest.Response.Event;
import alien4cloud.plugin.Janus.rest.RestClient;
import alien4cloud.plugin.Janus.utils.MappingTosca;
import alien4cloud.plugin.Janus.utils.ShowTopology;
import alien4cloud.topology.TopologyUtils;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.tosca.catalog.index.IToscaTypeSearchService;
import org.alien4cloud.tosca.exporter.ArchiveExportService;
import org.alien4cloud.tosca.model.CSARDependency;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.DeploymentArtifact;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.ScalingPolicy;
import org.alien4cloud.tosca.model.templates.Topology;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.alien4cloud.tosca.model.types.RelationshipType;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.elasticsearch.common.collect.Maps;

import javax.inject.Inject;

/**
 * deployment task
 */
@Slf4j
public class DeployTask extends AlienTask {
    // Needed Info
    PaaSTopologyDeploymentContext ctx;
    IPaaSCallback<?> callback;

    private ArchiveExportService archiveExportService = new ArchiveExportService();

    private final int JANUS_DEPLOY_TIMEOUT = 1000 * 3600 * 24;  // 24 hours

    public DeployTask(PaaSTopologyDeploymentContext ctx, JanusPaaSProvider prov, IPaaSCallback<?> callback) {
        super(prov);
        this.ctx = ctx;
        this.callback = callback;
    }

    /**
     * Execute the Deployment
     */
    public void run() {
        Throwable error = null;

        // Keep Ids in a Map
        String paasId = ctx.getDeploymentPaaSId();
        String alienId = ctx.getDeploymentId();
        String deploymentUrl = "/deployments/" + paasId;
        log.debug("Deploying " + paasId + "with id : " + alienId);
        orchestrator.putDeploymentId(paasId, alienId);

        // Init Deployment Info from topology
        DeploymentTopology dtopo = ctx.getDeploymentTopology();
        Map<String, Map<String, InstanceInformation>> curinfo = setupInstanceInformations(dtopo);
        JanusRuntimeDeploymentInfo jrdi = new JanusRuntimeDeploymentInfo(ctx, DeploymentStatus.INIT_DEPLOYMENT, curinfo, deploymentUrl);
        orchestrator.putDeploymentInfo(paasId, jrdi);
        orchestrator.doChangeStatus(paasId, DeploymentStatus.INIT_DEPLOYMENT);

        // Show Topoloy for debug
        // ShowTopology.topologyInLog(ctx);

        // Change topology to be suitable for janus and tosca
        MappingTosca.addPreConfigureSteps(ctx);
        MappingTosca.generateOpenstackFIP(ctx);
        MappingTosca.quoteProperties(ctx);

        // Get the yaml of the application as built by from a4c
        Csar myCsar = new Csar(paasId, dtopo.getArchiveVersion());
        String yaml = archiveExportService.getYaml(myCsar, dtopo);

        // This operation must be synchronized, because it uses the same files topology.yml and topology.zip
        String taskUrl;
        synchronized(this) {
            // This is for debug only
            Path orig = Paths.get("original.yml");
            List<String> lines = Collections.singletonList(yaml);
            try {
                Files.write(orig, lines, Charset.forName("UTF-8"));
            } catch (IOException e) {
                log.warn("Cannot create original.yml");
            }

            // Create the yml of our topology and build our zip topology
            try {
                buildZip(ctx, yaml);
            } catch (IOException e) {
                orchestrator.doChangeStatus(paasId, DeploymentStatus.FAILURE);
                callback.onFailure(e);
                return;
            }

            // put topology zip to Janus
            log.info("PUT Topology to janus");
            try {
                taskUrl = restClient.putTopologyToJanus(paasId);
            } catch (Exception e) {
                orchestrator.sendMessage(paasId, "Deployment not accepted by janus: " + e.getMessage());
                orchestrator.doChangeStatus(paasId, DeploymentStatus.FAILURE);
                callback.onFailure(e);
                return;
            }
        }
        String taskId = taskUrl.substring(taskUrl.lastIndexOf("/") + 1);
        jrdi.setDeployTaskId(taskId);
        orchestrator.sendMessage(paasId, "Deployment sent to Janus. TaskId=" + taskId);

        // Listen Events and logs from janus about the deployment
        orchestrator.addTask(new EventListenerTask(ctx, orchestrator));
        orchestrator.addTask(new LogListenerTask(ctx, orchestrator));

        // wait for janus deployment completion
        boolean done = false;
        long timeout = System.currentTimeMillis() + JANUS_DEPLOY_TIMEOUT;
        Event evt;
        while (!done && error == null) {
            synchronized (jrdi) {
                // Check deployment timeout
                long timetowait = timeout - System.currentTimeMillis();
                if (timetowait <= 0) {
                    log.warn("Deployment Timeout occured");
                    error = new Throwable("Deployment timeout");
                    orchestrator.doChangeStatus(paasId, DeploymentStatus.FAILURE);
                    break;
                }
                // Wait Deployment Events from Janus
                log.debug(paasId + ": Waiting for deployment events.");
                try {
                    jrdi.wait(timetowait);
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for deployment");
                }
                // Check if we received a Deployment Event and process it
                evt = jrdi.getLastEvent();
                if (evt != null && evt.getType().equals(EventListenerTask.EVT_DEPLOYMENT)) {
                    jrdi.setLastEvent(null);
                    switch (evt.getStatus()) {
                        case "deployment_failed":
                            log.warn("Deployment failed: " + paasId);
                            orchestrator.doChangeStatus(paasId, DeploymentStatus.FAILURE);
                            error = new Exception("Deployment failed");
                            break;
                        case "deployed":
                            log.debug("Deployment success: " + paasId);
                            orchestrator.doChangeStatus(paasId, DeploymentStatus.DEPLOYED);
                            done = true;
                            break;
                        case "deployment_in_progress":
                            orchestrator.doChangeStatus(paasId, DeploymentStatus.DEPLOYMENT_IN_PROGRESS);
                            break;
                        default:
                            orchestrator.sendMessage(paasId, "Deployment status = " + evt.getStatus());
                            break;
                    }
                    continue;
                }
            }
            // We were awaken for some bad reason or a timeout
            // Check Deployment Status to decide what to do now.
            String status;
            try {
                status = restClient.getStatusFromJanus(deploymentUrl);
            } catch (Exception e) {
                // TODO Check error 404
                // assumes it is undeployed
                status = "UNDEPLOYED";
            }
            switch (status) {
                case "UNDEPLOYED":
                    orchestrator.changeStatus(paasId, DeploymentStatus.UNDEPLOYED);
                    error = new Throwable("Deployment has been undeployed");
                    break;
                case "DEPLOYED":
                    // Deployment is OK.
                    orchestrator.changeStatus(paasId, DeploymentStatus.DEPLOYED);
                    done = true;
                    break;
                default:
                    log.debug("Deployment Status is currently " + status);
                    break;
            }
        }
        synchronized (jrdi) {
            // Task is ended: Must remove the taskId and notify a possible undeploy waiting for it.
            jrdi.setDeployTaskId(null);
            jrdi.notify();
        }
        // Return result to a4c
        if (error == null) {
            callback.onSuccess(null);
        } else {
            callback.onFailure(error);
        }
    }

    // supported locations
    private final int LOC_OPENSTACK = 1;
    private final int LOC_KUBERNETES = 2;
    private final int LOC_AWS = 3;
    private final int LOC_SLURM = 4;

    /**
     * Create the zip for janus, with a modified yaml and all needed archives.
     * Assumes a file original.yml exists in the current directory
     * @param ctx all needed information about the deployment
     * @param yaml original yml
     * @throws IOException
     */
    private void buildZip(PaaSTopologyDeploymentContext ctx, String yaml) throws IOException {
        // Check location
        int location = LOC_OPENSTACK;
        Location loc = ctx.getLocations().get("_A4C_ALL");
        Set<CSARDependency> locdeps = loc.getDependencies();
        for (CSARDependency dep : locdeps) {
            if (dep.getName().contains("kubernetes")) {
                location = LOC_KUBERNETES;
                break;
            }
            if (dep.getName().contains("slurm")) {
                location = LOC_SLURM;
                break;
            }
            if (dep.getName().contains("aws")) {
                location = LOC_AWS;
                break;
            }
        }

        // Split yaml in lines
        String [] yamllines = yaml.split("\n");
        
        // Final zip file will be named topology.zip
        File zip = new File("topology.zip");
        OutputStream out = new FileOutputStream(zip);
        ZipOutputStream zout = new ZipOutputStream(out);
        Closeable res = zout;

        // Parse original.yml to construct a correct topology.yml and the zip file
        // containing all needed files.
        String topoFileName = "topology.yml";
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(topoFileName));
            boolean inimport = false;
            for (String line : yamllines) {
                if (inimport) {
                    if (line.contains(":")) {
                        if (line.contains("janus-")) {
                            // Use the janus types, without giving the version nb.
                            String ymlPath = line.substring(line.indexOf("janus-"), line.lastIndexOf(":"));
                            bw.append("  - path: <").append(ymlPath).append(".yml>\n");
                        } else if (line.contains("tosca-normative-types")) {
                            // Not needed: forget the line
                        } else {
                            // Add this csar to zip file
                            String pack = line.substring(line.indexOf("- ") + 2);
                            String module = pack.substring(0, pack.indexOf(":"));
                            String version = pack.substring(pack.indexOf(":") + 1);
                            String localpath = csar2zip(zout, module, version, location);
                            bw.append("  - path: ").append(localpath).append("\n");
                        }
                        continue;
                    }
                    inimport = false;
                }
                if (line.startsWith("imports:")) {
                    inimport = true;
                }
                // Write line without change
                bw.append(line).append("\n");
            }
        } catch (IOException e) {
            log.error("Error while building zip: " + e);
            throw e;
        } finally {
            try {
                if (bw != null)
                    bw.close();
            } catch (IOException e) {
                log.error("Error closing " + topoFileName, e);
            }
        }
        // Copy overwritten artifacts for each node
        PaaSTopology ptopo = ctx.getPaaSTopology();
        for (PaaSNodeTemplate node : ptopo.getAllNodes().values()) {
            copyArtifacts(node, zout);
        }

        // Copy modified topology
        createZipEntries(topoFileName, zout);
        copy(new File(topoFileName), zout);

        zout.closeEntry();
        res.close();
    }

    private File matchKubernetesImplementation(File fileToRead) throws IOException {
        File file = new File("tmp2.yml");
        file.createNewFile();

        FileWriter fw = new FileWriter(file);
        BufferedWriter bw = new BufferedWriter(fw);
        PrintWriter out = new PrintWriter(bw);

        FileReader fr = new FileReader(fileToRead);
        BufferedReader fin = new  BufferedReader(fr);
        String line;
        boolean clean = false;
        for (; ; ) {
            // Read a line.
            line = fin.readLine();
            if (line == null) {
                break;
            } else if (line.contains("tosca.artifacts.Deployment.Image.Container.Docker")) {
                out.println(line+".Kubernetes");
            } else {
                out.println(line);
            }
        }
        out.close();
        return file;
    }

    /**
     * Copy artifacts to archive
     * @param node
     * @param zout
     */
    private void copyArtifacts(PaaSNodeTemplate node, ZipOutputStream zout) {
        String name = node.getId();

        // Check if this component has artifacts
        Map<String, DeploymentArtifact> map = node.getTemplate().getArtifacts();
        if (map == null) {
            log.debug("Component with no artifact: " + name);
            return;
        }

        // Process each artifact
        for (Map.Entry<String, DeploymentArtifact> da : map.entrySet()) {
            String aname =  name + "/" + da.getKey();
            DeploymentArtifact artifact = da.getValue();
            String artRepo = artifact.getArtifactRepository();
            if (artRepo == null) {
                continue;
            }
            ShowTopology.printArtifact(artifact);
            if  (artRepo.equals(ArtifactRepositoryConstants.ALIEN_TOPOLOGY_REPOSITORY)) {
                // Copy artifact from topology repository to the root of archive.
                String from = artifact.getArtifactPath();
                log.debug("Copying local artifact: " + aname + " path=" + from);
                Path artifactPath = Paths.get(from);
                try {
                    String filename = artifact.getArtifactRef();
                    createZipEntries(filename, zout);
                    copy(artifactPath.toFile(), zout);
                } catch (Exception e) {
                    log.error("Could not copy local artifact " + aname, e);
                }
            } else {
                // Copy remote artifact
                String from = artifact.getArtifactPath();
                log.debug("Copying remote artifact: " + aname + " path=" + from);
                Path artifactPath = Paths.get(from);
                try {
                    String filename = artifact.getArtifactRef();
                    createZipEntries(filename, zout);
                    copy(artifactPath.toFile(), zout);
                } catch (Exception e) {
                    log.error("Could not copy remote artifact " + aname, e);
                }
                // Workaround for a bug in a4c: artifact not added in topology.yml
                // TODO Remove this when a4c bug SUPALIEN-926 is fixed.
                addRemoteArtifactInTopology(name, da.getKey(), artifact);
            }
        }
    }

    /**
     * Workaround for a4c issue: SUPALIEN-926
     * TODO Remove this when a4c bug is fixed. (planned for 1.5)
     * @param node Node Name
     * @param key Name of the artifact
     * @param da
     */
    private void addRemoteArtifactInTopology(String node, String key, DeploymentArtifact da) {
        log.debug("");
        String oldFileName = "topology.yml";
        String tmpFileName = "tmp_topology.yml";

        log.debug("Add remote artifact in topology (workaround for SUPALIEN-926)");
        log.debug(node + " " + key + " : " + da.getArtifactRef() + " - " + da.getArtifactType());

        BufferedReader br = null;
        BufferedWriter bw = null;

        try {
            bw = new BufferedWriter(new FileWriter(tmpFileName));
            br = new BufferedReader(new FileReader(oldFileName));
            String line;
            boolean inNode = false;
            boolean done = false;
            while ((line = br.readLine()) != null) {
                if (! done) {
                    if (line.startsWith("    " + node + ":")) {
                        inNode = true;
                        bw.append(line).append("\n");
                        continue;
                    }
                    if (! inNode) {
                        bw.append(line).append("\n");
                        continue;
                    }
                    if (! line.startsWith("      ")) {
                        bw.append("      artifacts:\n");
                        // Add here the 3 lines to describe the remote artifact
                        String l1 = "        " + key + ":\n";
                        String l2 = "          file: " + da.getArtifactRef() + "\n";
                        String l3 = "          type: " + da.getArtifactType() + "\n";
                        bw.append(l1).append(l2).append(l3);
                        done = true;
                        bw.append(line).append("\n");
                        continue;
                    }
                    if (line.startsWith("      artifacts:")) {
                        bw.append(line).append("\n");
                        // Add here the 3 lines to describe the remote artifact
                        String l1 = "        " + key + ":\n";
                        String l2 = "          file: " + da.getArtifactRef() + "\n";
                        String l3 = "          type: " + da.getArtifactType() + "\n";
                        bw.append(l1).append(l2).append(l3);
                        done = true;
                        continue;
                    }
                }
                bw.append(line).append("\n");
            }
        } catch (Exception e) {
            log.error("Error while modifying topology.yml");
            return;
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (IOException e) {
                log.error("Error closing " + oldFileName, e);
            }
            try {
                if (bw != null)
                    bw.close();
            } catch (IOException e) {
                log.error("Error closing " + tmpFileName, e);
            }
        }
        // Once everything is complete, delete old file..
        File oldFile = new File(oldFileName);
        oldFile.delete();

        // And rename tmp file's name to old file name
        File newFile = new File(tmpFileName);
        newFile.renameTo(oldFile);
    }

    /**
     * Get csar and add entries in zip file for it
     * @return relative path to the yml, ex: welcome-types/3.0-SNAPSHOT/welcome-types.yaml
     */
    private String csar2zip(ZipOutputStream zout, String module, String version, int location) {
        // Get path directory to the needed info:
        // should be something like: ...../runtime/csar/<module>/<version>/expanded
        // We should have a yml or a yaml here
        Path csarpath = orchestrator.getCSAR(module, version);
        String dirname = csarpath.toString();
        File directory = new File(dirname);
        String relative = dirname.substring(dirname.indexOf("csar/") + 5);
        relative = relative.substring(0, relative.lastIndexOf("/") + 1);
        String ret = relative;
        try {
            // All files under this directory must be put in the zip
            URI base = directory.toURI();
            Deque<File> queue = new LinkedList<>();
            queue.push(directory);
            while (!queue.isEmpty()) {
                directory = queue.pop();
                for (File kid : directory.listFiles()) {
                    String name = base.relativize(kid.toURI()).getPath();
                    if (kid.isDirectory()) {
                        queue.push(kid);
                    } else {
                        File file = kid;
                        createZipEntries(relative + name, zout);
                        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                            ret += name;
                            // Remove all imports, since they should be all in the root yml
                            Set<String> end_token = new HashSet<>(Arrays.asList("node_types:", "repositories:"));
                            file = removeLineBetween(kid, "imports:", end_token);
                            if (location == LOC_KUBERNETES) {
                                file = matchKubernetesImplementation(file);
                            }
                        }
                        copy(file, zout);
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return ret;
    }

    private File removeLineBetween(File fileToRead, String begin, Set<String> end) throws IOException {
        File file = new File("tmp.yml");
        file.createNewFile();

        FileWriter fw = new FileWriter(file);
        BufferedWriter bw = new BufferedWriter(fw);
        PrintWriter out = new PrintWriter(bw);

        FileReader fr = new FileReader(fileToRead);
        BufferedReader fin = new  BufferedReader(fr);
        String line;
        boolean clean = false;
        for (; ; ) {
            // Read a line.
            line = fin.readLine();
            if (line == null) {
                break;
            }
            if (!clean) {
                out.println(line);
            }
            if (line.contains(begin)) {
                clean = true;
            } else if (end.contains(line)) {
                out.println(line);
                clean = false;
            }
        }
        out.close();
        return file;
    }

    /**
     * Add all ZipEntry for this file path
     * If path is a directory, it must be ended by a "/".
     * All directory entries must be ended by a "/", and all simple file entries must be not.
     * TODO use this method everywhere
     * @param fullpath
     * @param zout
     */
    private void createZipEntries(String fullpath, ZipOutputStream zout) throws IOException {
        log.debug("createZipEntries for " + fullpath);
        int index = 0;
        String name = "";
        while (name.length() < fullpath.length()) {
            index = fullpath.indexOf("/", index) + 1;
            if (index <= 1) {
                name = fullpath;
            } else {
                name = fullpath.substring(0, index);
            }
            try {
                zout.putNextEntry(new ZipEntry(name));
                log.debug("new ZipEntry: " + name);
            } catch (ZipException e) {
                if (e.getMessage().contains("duplicate")) {
                    //log.debug("ZipEntry already added: " + name);
                } else {
                    log.error("Cannot add ZipEntry: " + name, e);
                    throw e;
                }
            }
        }
    }

    private Map<String, Map<String, InstanceInformation>> setupInstanceInformations(Topology topology) {
        log.debug("setupInstanceInformations for " + topology.getArchiveName() + " : " + topology.getArchiveVersion());
        Map<String, NodeTemplate> nodeTemplates = topology.getNodeTemplates();
        if (nodeTemplates == null) {
            nodeTemplates = Maps.newHashMap();
        }
        Map<String, Map<String, InstanceInformation>> currentInformations = Maps.newHashMap();
        for (Entry<String, NodeTemplate> nodeTemplateEntry : nodeTemplates.entrySet()) {
            Map<String, InstanceInformation> instanceInformations = Maps.newHashMap();
            currentInformations.put(nodeTemplateEntry.getKey(), instanceInformations);
            ScalingPolicy policy = getScalingPolicy(nodeTemplateEntry.getKey(), nodeTemplates);
            int initialInstances = policy != null ? policy.getInitialInstances() : 1;
            for (int i = 0; i < initialInstances; i++) {
                InstanceInformation newInstanceInformation = orchestrator.newInstance(i);
                instanceInformations.put(String.valueOf(i), newInstanceInformation);
            }
        }
        return currentInformations;
    }

    private ScalingPolicy getScalingPolicy(String id, Map<String, NodeTemplate> nodeTemplates) {
        // Get the scaling of parent if not exist
        Capability scalableCapability = TopologyUtils.getScalableCapability(nodeTemplates, id, false);
        if (scalableCapability != null) {
            return TopologyUtils.getScalingPolicy(scalableCapability);
        }
        if (nodeTemplates.get(id).getRelationships() != null) {
            for (RelationshipTemplate rel : nodeTemplates.get(id).getRelationships().values()) {
                RelationshipType relType = orchestrator.getRelationshipType(rel.getType());
                if (isOfType(relType, NormativeRelationshipConstants.HOSTED_ON)) {
                    return getScalingPolicy(rel.getTarget(), nodeTemplates);
                }
            }
        }
        return null;
    }

}
