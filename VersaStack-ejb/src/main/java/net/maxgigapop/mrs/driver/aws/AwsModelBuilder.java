/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.maxgigapop.mrs.driver.aws;


import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntModelSpec;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.maxgigapop.mrs.bean.ModelBase;
import net.maxgigapop.mrs.common.*;
import net.maxgigapop.mrs.driver.openstack.OpenStackModelBuilder;

/*
 *
 * @author muzcategui
 */
public class AwsModelBuilder 
{
    public static OntModel createOntology(String access_key_id,String secret_access_key, Regions region) throws IOException
    {
        Logger logger = Logger.getLogger(AwsModelBuilder.class.getName());
        
        //create model object
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF); 
        
        //set all the model prefixes
        model.setNsPrefix("rdfs",RdfOwl.getRdfsURI());
        model.setNsPrefix("rdf", RdfOwl.getRdfURI());
        model.setNsPrefix("xsd", RdfOwl.getXsdURI());
        model.setNsPrefix("owl", RdfOwl.getOwlURI());
        model.setNsPrefix("nml", Nml.getURI());
        model.setNsPrefix("mrs", Mrs.getURI());
        
        //set the global properties
        Property hasNode =Nml.hasNode;
        Property hasBidirectionalPort=Nml.hasBidirectionalPort;
        Property hasService =Nml.hasService;
        Property providesVM =Mrs.providesVM;
        Property type = RdfOwl.type;
        Property providedByService =Mrs.providedByService;
        Property providesBucket =Mrs.providesBucket;
        Property providesRoute =Mrs.providesRoute;
        Property providesSubnet =Mrs.providesSubnet;
        Property providesVPC = Mrs.providesVPC;
        Property providesVolume = Mrs.providesVolume;
        Property routeFrom = Mrs.routeFrom;
        Property routeTo =Mrs.routeTo;
        Property nextHop=Mrs.nextHop;
        Property value =Mrs.value;
        Property name=Nml.name;
        Property publicIpAddress=model.createProperty(model.getNsPrefixURI("mrs")+"publicIpAddress");
        Property privateIpAddress=model.createProperty(model.getNsPrefixURI("mrs")+"privateIpAddress");
        
        //set the global resources
        Resource route=Mrs.Route;
        Resource hypervisorService=Mrs.HypervisorService;
        Resource storageService=Mrs.StorageService;
        Resource virtualCloudService =Mrs.VirtualCloudService;
        Resource routingService = Mrs.RoutingService;
        Resource blockStorageService = Mrs.BlockStorageService;
        Resource bucket = Mrs.Bucket;
        Resource volume = Mrs.Volume;
        Resource topology =Nml.Topology;
        Resource networkAddress=Mrs.NetworkAddress;
        Resource switchingSubnet=Mrs.SwitchingSubnet;
        Resource switchingService=Mrs.SwitchingService;
        Resource node =Nml.Node;
        Resource port = Nml.BidirectionalPort;
        Resource namedIndividual = model.createResource(model.getNsPrefixURI("mrs")+"NamedIndividual");
        Resource awsTopology = RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:AwsTopology",topology);
        Resource objectStorageService=Mrs.ObjectStorageService;
        
        //get the information from the AWS account
        AwsEC2Get ec2Client=new AwsEC2Get(access_key_id,secret_access_key,region);
        AwsS3Get s3Client=new AwsS3Get(access_key_id,secret_access_key,region);
        AwsDCGet dcClient= new AwsDCGet(access_key_id,secret_access_key,region);
        
        
        //create the outer layer of the aws model
        Resource ec2Service=RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:ec2service-"+ec2Client.getClient().toString(),hypervisorService);
        Resource vpcService=RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:vpcservice-"+ec2Client.getClient().toString(),virtualCloudService);
        Resource s3Service= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:s3service-"+s3Client.getClient().toString(),objectStorageService);
        Resource ebsService= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:ebsservice-"+ec2Client.getClient().toString(),blockStorageService);
        
        model.add(model.createStatement(awsTopology, type, topology));
        model.add(model.createStatement(awsTopology, type, namedIndividual));
        
        model.add(model.createStatement(awsTopology,hasService,ec2Service));
        model.add(model.createStatement(awsTopology, hasService, vpcService));
        model.add(model.createStatement(awsTopology,hasService,s3Service));
        model.add(model.createStatement(awsTopology,hasService,ebsService));

        
        //put all the vpcs and their information into the model
        for(Vpc v : ec2Client.getVpcs())
        {
            Resource VPC = RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+ v.getVpcId(),topology);
            model.add(model.createStatement(vpcService,providesVPC,VPC));
            
            //put all the subnets within the vpc
            Resource SWITCHINGSERVICE= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+v.getVpcId()+v.getCidrBlock(),switchingService);
            for(Subnet p: AwsEC2Get.getSubnets(ec2Client.getSubnets(), v.getVpcId()))
            {
                Resource SUBNET= RdfOwl.createResource(model,"urn:ogf:network:dragon.maxgigapop.net:"+p.getSubnetId(),switchingSubnet);
                model.add(model.createStatement(VPC, hasService,SWITCHINGSERVICE));
                model.add(model.createStatement(SWITCHINGSERVICE, providesSubnet, SUBNET));
            
                //put all the intances inside this subnet into the model if there are any
                List<Instance> instances = ec2Client.getInstances(p.getSubnetId());
                if(!instances.isEmpty())
                {
                    for(Instance i : instances)
                    {
                        Resource INSTANCE= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+i.getInstanceId(),node);
                        model.add(model.createStatement(VPC,hasNode, INSTANCE));
                        model.add(model.createStatement(INSTANCE, providedByService,ec2Service));

                        //put all the network interfaces of each instance into the model
                        for(InstanceNetworkInterface n : AwsEC2Get.getInstanceInterfaces(i))
                        {
                            Resource PORT = RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+n.getNetworkInterfaceId(),port);

                            model.add(model.createStatement(INSTANCE,hasBidirectionalPort,PORT));
                            model.add(model.createStatement(SUBNET,hasBidirectionalPort,PORT));
                            
                            //put the private ip (if any) of the network interface in the model
                            for(InstancePrivateIpAddress q : n.getPrivateIpAddresses())
                            {
                                if(q.getPrivateIpAddress()!=null)
                                {
                                    Resource PRIVATE_ADDRESS= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+q.getPrivateIpAddress(),networkAddress);
                                    model.add(model.createStatement(PORT, privateIpAddress, PRIVATE_ADDRESS));
                                    model.add(model.createStatement(PRIVATE_ADDRESS,value,q.getPrivateIpAddress()));
                                }
                            }
                            
                            //put the public Ip (if any) of the network interface into the model
                            if(n.getAssociation() !=null && n.getAssociation().getPublicIp()!=null)
                            {
                                Resource PUBLIC_ADDRESS= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+n.getAssociation().getPublicIp(),networkAddress);
                                model.add(model.createStatement(PORT, publicIpAddress, PUBLIC_ADDRESS));
                                model.add(model.createStatement(PUBLIC_ADDRESS,value,n.getAssociation().getPublicIp()));
                            }
                        }
                    }
                }
            }  
            
            //get all the routes inside this VPC
            for(RouteTable t : AwsEC2Get.getRoutingTables(ec2Client.getRoutingTables(),v.getVpcId()))
            {
               
                Resource ROUTINGSERVICE=RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+t.getRouteTableId(),routingService);
                model.add(model.createStatement(VPC, hasService,ROUTINGSERVICE));
                List<Route> routes= t.getRoutes();
                for(Route r: routes)
                {
                    Resource ROUTE= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+t.getRouteTableId()+r.getDestinationCidrBlock()+r.getState(),route);
                    model.add(model.createStatement(ROUTINGSERVICE,providesRoute,ROUTE));
                    //model.add(model.createStatement(ROUTE, routeFrom,r.getOrigin()));
                    //model.add(model.createStatement(ROUTE,routeTo,r.getDestinationCidrBlock()));
                }
            }
         }
        
        //put the volumes of the ebsService into the model
        for(Volume v : ec2Client.getVolumes())
        {
            Resource VOLUME= RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+v.getVolumeId(),volume);
            model.add(model.createStatement(ebsService,providesVolume, VOLUME));
            model.add(model.createStatement(VOLUME, value,v.getVolumeType()));
        }
        
        //put all the buckets of the s3Service into the model
        for(Bucket b: s3Client.getBuckets())
        {
            Resource BUCKET = RdfOwl.createResource(model,"urn:ogf:network:aws.amazon.com:aws-cloud:"+ b.getName(),bucket);
            model.add(model.createStatement(s3Service, providesBucket,BUCKET));
            
           /* for(S3ObjectSummary o :   s3Client.getObjects(b))
            {
                Resource OBJECT=model.createResource("urn:ogf:network:aws.amazon.com:aws-cloud:"+ o.getKey());
                model.add(model.createStatement(BUCKET,hasObject,OBJECT));
                model.add(model.createStatement(OBJECT,type,object));
                model.add(model.createStatement(OBJECT,type,namedIndividual));
            }*/
        }
        
        logger.log(Level.INFO, "Ontology model for AWS driver rewritten");
        return model;
    }
}
