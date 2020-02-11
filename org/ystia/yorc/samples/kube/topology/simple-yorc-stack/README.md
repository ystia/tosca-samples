# Simple Yorc stack application template

Defines an application template that alows to deploy a Yorc Stack composed by Yorc&Consul + Alien4Cloud.
The A4C used to deploy the application has version 2.2.0-SM10

Application is composed by the following components that need to already be uploaded in the A4C catalog:
- org.ystia.yorc.samples.kube.yorcconsul
- org.ystia.yorc.samples.kube.alien


## Usage

Check used versions in this template and in components types, and if necessary modify them correspondingly to your needs. 
Create a zip file with the types.yaml and upload it to the A4C's Catalog.

Create an application using this template.

Deploy it to a K8S location configured within a Yorc orchestrator (YO).

## A4C Administration config

### Create usefull Meta-properties

- K8S_NAMESPACE
- YORC_LOCATION 

### K8S location configuration

#### On demand resources

- org.alien4cloud.kubernetes.api.types.Deployment
- org.alien4cloud.kubernetes.api.types.Container
- org.alien4cloud.kubernetes.api.types.volume.ConfigMapSource
- NodePort org.alien4cloud.kubernetes.api.types.Service
- LoadBalancer org.alien4cloud.kubernetes.api.types.Service

#### Meta-properties

- K8S_NAMESPACE = namespace name to be used
- YORC_LOCATION = the K8S location name configured in the YO