/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.maxgigapop.mrs.driver.aws;


import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ec2.AmazonEC2Client;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author muzcategui
 */
public class AwsEC2Get 
{
    private AmazonEC2Client client=null;
    private List<Vpc> vpcs=null;
    private List<Instance> instances=null;
    private List<Subnet> subnets=null;
    private List<SecurityGroup> securityGroups=null;
    private List<NetworkAcl> acls=null;
    private List<RouteTable> routeTables=null;
    private List<Address> elasticIps=null;
    private List<CustomerGateway> customerGateways=null;
    private List<Volume> volumes=null;
    private List<NetworkInterface> networkInterfaces=null;
    private List<InternetGateway> internetGateways=null;
    private List<VpnGateway> virtualPrivateGateways = null;
    
    public AwsEC2Get(String access_key_id, String secret_access_key,Regions region)
    {
        AwsAuthenticateService authenticate=new AwsAuthenticateService(access_key_id,secret_access_key);
        this.client = authenticate.AwsAuthenticateEC2Service(Region.getRegion(region));
        
        //get all the vpcs of the account
        DescribeVpcsResult VpcsResult=this.client.describeVpcs();
        this.vpcs=VpcsResult.getVpcs();
        
        //get all the instances of the account
        DescribeInstancesResult instancesResult=this.client.describeInstances();
        List<Reservation> reservation=instancesResult.getReservations();
        instances=new ArrayList();
        if(reservation.size()>=1)
            for(Reservation t : reservation)
            {
               instances.add(t.getInstances().get(0));
            }
      
        //get all the subnets in the account
        DescribeSubnetsResult subnetsResult=this.client.describeSubnets();
        this.subnets=subnetsResult.getSubnets();
 
        //get all the security groups in the account
        DescribeSecurityGroupsResult securityGroupsResult=this.client.describeSecurityGroups();
        this.securityGroups=securityGroupsResult.getSecurityGroups();
        
        //get all the Acls under the account
        DescribeNetworkAclsResult aclsResult=this.client.describeNetworkAcls();
        acls=aclsResult.getNetworkAcls();
        
        //get all the routeTables of the account 
        DescribeRouteTablesResult tablesResult=this.client.describeRouteTables();
        routeTables=tablesResult.getRouteTables();
        
        //get all the elastic Ip's under the account
        DescribeAddressesResult elasticIpsResult=this.client.describeAddresses();
        elasticIps=elasticIpsResult.getAddresses();
        
        //get all the network interfaces under the account
        DescribeNetworkInterfacesResult networkInterfacesResult=this.client.describeNetworkInterfaces();
        networkInterfaces=networkInterfacesResult.getNetworkInterfaces();
        
        //get all the customer gatewyas under the account
        DescribeCustomerGatewaysResult gatewaysResult=this.client.describeCustomerGateways();
        customerGateways=gatewaysResult.getCustomerGateways();
        
        //get all the Internet gateways under the account
        DescribeInternetGatewaysResult internetGatewaysResult=this.client.describeInternetGateways();
        internetGateways = internetGatewaysResult.getInternetGateways();
        
        //get all the virtual private gateways under the account
        DescribeVpnGatewaysResult vpnGatewaysResult = this.client.describeVpnGateways();
        virtualPrivateGateways =vpnGatewaysResult.getVpnGateways();
        
        //get all the volumes under the account
        DescribeVolumesResult volumesResult=this.client.describeVolumes();
        volumes=volumesResult.getVolumes();
    }
    
    //get the client of this EC2 Account account
    public AmazonEC2Client getClient()
    {
        return client;
    }
    //get the list of all the VPCs of the client
    public  List<Vpc>  getVpcs()
    {
       return vpcs;
    }
    
    //get a single Vpc based on its ID  from a list of Vpcs
    public  Vpc  getVpc(String id)
    {
        for (Vpc vpc : vpcs) {
            if(vpc.getVpcId().equals(id))
                return vpc;
        }
        return null;
    }
    
    //get all the instances under the aws account
    public List<Instance> getEc2Instances()
    {
        return instances;
    }
    
    //get all the instances associated with a Vpc  or subnet in the account
    public List<Instance> getInstances(String id )
    {
        List<Instance> ins= new ArrayList();
        for(Instance i: instances)
        {
            if(i.getVpcId()!=null && i.getVpcId().equals(id))
                ins.add(i);
            if(i.getSubnetId()!=null && i.getSubnetId().equals(id))
                ins.add(i);
        }
        return ins;
    }
    
    //get all the instances associated with a Vpc  or subnet in the account
    public   Instance getInstance(String id )
    {
        for(Instance i: instances)
        {
            if(i.getInstanceId().equals(id))
                return i;
        }
        return null;
    }
    
        
    //get all the attached network interfaces for an instance
    public static List<InstanceNetworkInterface> getInstanceInterfaces(Instance i)
    {
        return i.getNetworkInterfaces();
    }
    
    //get all the network interfaces under the account
    public List<NetworkInterface> getNetworkInterfaces()
    {
        return networkInterfaces;
    }
    
    //get all the network interfaces under the account
    public  NetworkInterface getNetworkInterface(String id)
    {
       for(NetworkInterface n : networkInterfaces)
        {
            if(n.getNetworkInterfaceId().equals(id))
            {
               return n;
            }
        }
        return null;
    }

    
    //get the list of all the subnets associated with an account
    public  List<Subnet>  getSubnets()
    {
        return subnets;
    }
    
    //get the subnet under a vpc or a single subnet  based on its Id from a list
    //of subnets
    public List<Subnet> getSubnets(String id)
    {
        List<Subnet> subnetList=new ArrayList();
        for(Subnet sub : subnets)
        {
            if(sub.getVpcId().equals(id))
                subnetList.add(sub);
            else if(sub.getSubnetId().equals(id))
            {
                subnetList.add(sub);
                return subnetList;
            }
        }
        return subnetList;
    }
    
    //get a single subnet based on its id
    public Subnet getSubnet(String id)
    {
        for(Subnet sub : subnets)
        {
            if(sub.getSubnetId().equals(id))
            {
               return sub;
            }
        }
        return null;
    }
    
    
    //get all the security groups from an AWS account
    public List<SecurityGroup> getSecurityGroups()
    {
       return securityGroups;
    }
    
    //get all the security groups from a speicific VPC or a single group based 
    //on either a vpc Id or a group Id  from a list of security groups
    public List<SecurityGroup> getSecurityGroups(String id)
    {
        List<SecurityGroup> group=new ArrayList();
        for(SecurityGroup gr : securityGroups )
        {
           if(gr.getVpcId().equals(id))
               group.add(gr);
           else if(gr.getGroupId().equals(id))
           {
               group.add(gr);
               return group;
           }
        }
        return group;
    }
    
    
    //get all the ACLs withinan AWS account
    public  List<NetworkAcl> getACLs()
    {
        return acls;
    }
    
    //get all the ACLs within a VPC or an ACl based on its id
    public List<NetworkAcl> getACLs( String id)
    {
       List<NetworkAcl> rules = new ArrayList();
       for(NetworkAcl t : acls)
       {
           if(t.getVpcId().equals(id))
               rules.add(t);
           else if(t.getNetworkAclId().equals(id))
           {
               rules.add(t);
               return rules;
           }
       }
       return rules;
    }
    
    
    //get all the routing tables under a vpc
    public  List<RouteTable> getRoutingTables()
    {
        return routeTables;
    }
    
    //get a single routing table based on its id 
    public RouteTable getRoutingTable(String id)
    {
        for(RouteTable t : routeTables)
        {
          if (t.getRouteTableId().equals(id))
              return t;
        }
        return null;               
    }
    
    //get all the routing tables under a vpc or a single route table based on id
    public  List<RouteTable> getRoutingTables(String id)
    {
        List<RouteTable> rt=new ArrayList();
        for(RouteTable t : routeTables)
        {
            if (t.getVpcId().equals(id))
                rt.add(t);
            else if(t.getRouteTableId().equals(id))
            {
                rt.add(t);
                return rt;
            }
        }
        return rt;
    }
    
    
    //get all the  elastic Ips under the account
    public List<Address> getElasticIps()
    {
        return elasticIps;
    }
    
    //get all the  elastic Ips under an 
    public  Address getElasticIp(String id)
    {
        for(Address t : elasticIps)
        {
            if(t.getPublicIp().equals(id))
            {
                return t;
            }
        }
        return null;
    }
    
    
    //get all the customer gateways under the aws account
    public  List<CustomerGateway> getCustomerGateways()
    {
        return customerGateways;
    }
    
    //get a specific customer gateway based on its id 
    public  CustomerGateway getCustomerGateway(String id)
    {
        for(CustomerGateway t : customerGateways)
        {
            if(t.getCustomerGatewayId().equals(id))
            {
                return t;
            }
        }
        return null;
    }
    
    //get all the internet gateways under the aws account
    public  List<InternetGateway> getInternetGateways()
    {
        return internetGateways;
    }
    
    //get a specific internet gateway based on its id or vpc id
    public InternetGateway getInternetGateway(String id)
    {
        for(InternetGateway t : internetGateways)
        {
            if(t.getInternetGatewayId().equals(id))
                return t;
        }
        return null;
    }
    
    //get the virtual private gateways
    public List<VpnGateway> getVirtualPrivateGateways()
    {
        return virtualPrivateGateways;
    }
    
    //get a specific virtual private gateway
    public VpnGateway getVirtualPrivateGateway(String id)
    {
        for(VpnGateway t : virtualPrivateGateways)
        {
            if(t.getVpnGatewayId().equals(id))
            {
                return t;
            }
        }
        return null;
    }
    
    
    
    //get all the volumes under the aws account
    public List<Volume> getVolumes()
    {
        return this.volumes;
    }
    
    //get a  volume with a particular Id from a list of volumes
    public Volume getVolume(String id)
    {  
        for(Volume v : volumes )
        {
            if(v.getVolumeId().equals(id))
            {
               return v;
            }
        }
        return null;
    }
    
    
    //get a list of all the volumes that have an attachement to an instance
    public List<Volume> getVolumesWithAttachement(Instance i)
    {
        List<Volume> volume=new ArrayList();
        for(Volume v: this.volumes)
        {
            for(VolumeAttachment va: v.getAttachments())
            {
                if(va.getInstanceId().equals(i.getInstanceId()) && !volume.contains(v))
                    volume.add(v);
            }
        }
        return volume;
    }
    
    //get a List of all the volumes withouh an attachement
    public List<Volume> getVolumesWithoutAttachment()
    {
        List<Volume> volume=new ArrayList();
        
        for(Volume v: this.volumes)
        {
            if(v.getAttachments().isEmpty())
            {
                volume.add(v);
            }
        }
        return volume;
    }
    
    //get Id tag returns the resource Id if no Id tags were found
    public String  getIdTag(String resourceId)
    {
        Filter filter= new Filter();
        filter.withName("resource-id")
              .withValues(resourceId);
        
        DescribeTagsRequest tagRequest= new DescribeTagsRequest();
        tagRequest.withFilters(filter);
        List<TagDescription> descriptions= this.client.describeTags(tagRequest).getTags();
        
        for(TagDescription td : descriptions)
        {
            if(td.getKey().equals("id"))
                return td.getValue();
        }
        return resourceId;
    }
}
