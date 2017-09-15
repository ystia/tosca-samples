/*
* Copyright 2016 Bull Atos.  All Rights Reserved.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* See the NOTICE file distributed with this work for additional information
* regarding copyright ownership.
*/

//TODO remove this class when the development end
// this class is a test in order to show all in information about the topology and know what kind of information
// we can get thanks to the PaaSTopologyDeploymentContext

package alien4cloud.plugin.Janus.utils;

import alien4cloud.component.repository.ArtifactLocalRepository;
import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.model.deployment.DeploymentTopology;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import alien4cloud.paas.wf.Workflow;
import alien4cloud.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.tosca.catalog.index.ToscaTypeSearchService;
import org.alien4cloud.tosca.catalog.repository.CsarFileRepository;
import org.alien4cloud.tosca.model.definitions.*;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeGroup;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Requirement;

import javax.annotation.Resource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class is used for debugging only.
 * It provides some utilities to print info about a deployment topology.
 */
@Slf4j
public class ShowTopology {

    /**
     * Print info about Artifact
     * @param da
     */
    public static void printArtifact(DeploymentArtifact da) {
        log.debug("*** Artifact : " + da.getArtifactName());
        log.debug("DeployPath=" + da.getDeployPath());
        log.debug("Archive=" + da.getArchiveName() + " " + da.getArchiveVersion());
        log.debug("ArtifactType=" + da.getArtifactType());
        log.debug("ArtifactPath=" + da.getArtifactPath());
        log.debug("ArtifactRepository=" + da.getArtifactRepository());
        log.debug("RepositoryName=" + da.getRepositoryName());
        log.debug("ArtifactRef=" + da.getArtifactRef());
    }

    /**
     * Print info about a Node
     * @param node
     */
    private static void printNode(PaaSNodeTemplate node) {
        log.debug("******* Compute Node " + node.getId() + " *******");
        NodeTemplate nt = node.getTemplate();

        log.debug("CsarPath = " + node.getCsarPath());
        log.debug("Type = " + nt.getType());

        // Children
        List<PaaSNodeTemplate> children = node.getChildren();
        for (PaaSNodeTemplate child : children) {
            log.info("Child: " + child.getId());
        }
        
        // properties
        for (String prop : nt.getProperties().keySet()) {
            AbstractPropertyValue absval = nt.getProperties().get(prop);
            if (absval instanceof ScalarPropertyValue) {
                ScalarPropertyValue scaval = (ScalarPropertyValue) absval;
                log.debug(">> Property: " + prop + "=" + scaval.getValue());
            }
        }

        // Attributes
        Map<String, IValue> attrs = nt.getAttributes();
        if (attrs != null) {
            for (String attname : attrs.keySet()) {
                IValue att = attrs.get(attname);
                log.debug(">> Attribute: " + attname + "=" + att);
            }
        }

        // capabilities
        Map<String, Capability> capabilities = nt.getCapabilities();
        if (capabilities != null) {
            for (String capname : capabilities.keySet()) {
                Capability cap = capabilities.get(capname);
                log.debug(">> Capability " + capname);
                log.debug("type : " + cap.getType());
                log.debug("properties : " + cap.getProperties());
            }
        }

        // requirements
        Map <String, Requirement> requirements = nt.getRequirements();
        if (requirements != null) {
            for (String reqname : requirements.keySet()) {
                Requirement req = requirements.get(reqname);
                log.debug(">> Requirement: " + reqname);
                log.debug("type : " + req.getType());
                log.debug("properties : " + req.getProperties());
            }
        }

        // relationships
        Map <String, RelationshipTemplate> relations = nt.getRelationships();
        if (relations != null) {
            for (String relname : relations.keySet()) {
                RelationshipTemplate rel = relations.get(relname);
                log.debug(">> Relationship: " + relname);
                log.debug("type : " + rel.getType());
                log.debug("properties : " + rel.getProperties());
            }
        }

        // artifacts
        Map <String, DeploymentArtifact> artifacts = nt.getArtifacts();
        if (artifacts != null) {
            for (DeploymentArtifact art : artifacts.values()) {
                printArtifact(art);
            }
        }

    }

    /**
     * Log topology infos for debugging
     * @param ctx
     */
    public static void topologyInLog(PaaSTopologyDeploymentContext ctx) {
        String paasId = ctx.getDeploymentPaaSId();
        PaaSTopology ptopo = ctx.getPaaSTopology();
        DeploymentTopology dtopo = ctx.getDeploymentTopology();

        // Deployment Workflows
        Map<String, Workflow> workflows = dtopo.getWorkflows();
        for (String wfname : workflows.keySet()) {
            log.debug("***** Workflow " + wfname);
            Workflow wf = workflows.get(wfname);
            log.debug("name: " + wf.getName());
            log.debug("host: " + wf.getHosts().toString());
            log.debug("steps: " + wf.getSteps().keySet().toString());
        }
        
        // Deployment Groups
        Map<String, NodeGroup> groups = dtopo.getGroups();
        if (groups != null) {
            for (String grname : groups.keySet()) {
                NodeGroup group = groups.get(grname);
                log.debug("***** Group " + grname);
                log.debug("name: " + group.getName());
                log.debug("members: " + group.getMembers().toString());
            }
        }

        // PaaS Compute Nodes
        for (PaaSNodeTemplate node : ptopo.getAllNodes().values()) {
            printNode(node);
        }
    }

}
