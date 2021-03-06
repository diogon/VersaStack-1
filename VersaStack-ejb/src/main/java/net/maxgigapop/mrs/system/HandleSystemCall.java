/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package net.maxgigapop.mrs.system;

import com.hp.hpl.jena.ontology.OntModel;
import java.io.StringWriter;
import static java.lang.Thread.sleep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.ejb.AsyncResult;
import javax.ejb.Asynchronous;
import javax.ejb.EJBException;
import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import net.maxgigapop.mrs.bean.DeltaBase;
import net.maxgigapop.mrs.bean.DriverInstance;
import net.maxgigapop.mrs.bean.DriverSystemDelta;
import net.maxgigapop.mrs.bean.ModelBase;
import net.maxgigapop.mrs.bean.SystemDelta;
import net.maxgigapop.mrs.bean.SystemInstance;
import net.maxgigapop.mrs.bean.VersionGroup;
import net.maxgigapop.mrs.bean.VersionItem;
import net.maxgigapop.mrs.bean.persist.DeltaPersistenceManager;
import net.maxgigapop.mrs.bean.persist.DriverInstancePersistenceManager;
import net.maxgigapop.mrs.bean.persist.ModelPersistenceManager;
import net.maxgigapop.mrs.bean.persist.PersistenceManager;
import net.maxgigapop.mrs.bean.persist.SystemInstancePersistenceManager;
import net.maxgigapop.mrs.bean.persist.VersionGroupPersistenceManager;
import net.maxgigapop.mrs.bean.persist.VersionItemPersistenceManager;
import net.maxgigapop.mrs.common.ModelUtil;
import net.maxgigapop.mrs.driver.IHandleDriverSystemCall;

/**
 *
 * @author xyang
 */
@Stateless
@LocalBean
public class HandleSystemCall {    
    public VersionGroup createHeadVersionGroup(String refUuid) {
        Map<String, DriverInstance> ditMap = DriverInstancePersistenceManager.getDriverInstanceByTopologyMap();
        if (ditMap == null) {
            DriverInstancePersistenceManager.refreshAll();
            ditMap = DriverInstancePersistenceManager.getDriverInstanceByTopologyMap();
        }
        if (ditMap.isEmpty()) {
           throw new EJBException(String.format("createHeadVersionGroup canont find driverInstance in the system"));
        }
        VersionGroup vg = new VersionGroup();
        for (String topoUri: ditMap.keySet()) {
            DriverInstance di = ditMap.get(topoUri);
            synchronized (di) {
                VersionItem vi = di.getHeadVersionItem();
                if (vi == null) {
                    throw new EJBException(String.format("createHeadVersionGroup encounters null head versionItem in %s", di));
                }
                vg.addVersionItem(vi);
            }
        }
        vg.setRefUuid(refUuid);
        VersionGroupPersistenceManager.save(vg);
        return vg;
    }
    
    public VersionGroup createHeadVersionGroup(String refUuid, List<String> topoURIs) {
        Map<String, DriverInstance> ditMap = DriverInstancePersistenceManager.getDriverInstanceByTopologyMap();
        if (ditMap == null) {
            DriverInstancePersistenceManager.refreshAll();
            ditMap = DriverInstancePersistenceManager.getDriverInstanceByTopologyMap();
        }
        if (ditMap.isEmpty()) {
           throw new EJBException(String.format("createHeadVersionGroup canont find driverInstance in the system"));
        }
        VersionGroup vg = new VersionGroup();
        for (String topoUri: topoURIs) {
            DriverInstance di = ditMap.get(topoUri);
            if (di == null) {
                throw new EJBException(String.format("createHeadVersionGroup canont find driverInstance with topologyURI=%s", topoUri));
            }
            VersionItem vi = di.getHeadVersionItem();
            if (vi == null) {
                throw new EJBException(String.format("createHeadVersionGroup encounters null head versionItem in %s", di));
            }
            vg.addVersionItem(vi);
            vi.addVersionGroup(vg);
        }
        vg.setRefUuid(refUuid);
        VersionGroupPersistenceManager.save(vg);
        return vg;
    }

    public VersionGroup updateHeadVersionGroup(String refUuid) {
        VersionGroup vg = VersionGroupPersistenceManager.findByReferenceId(refUuid);
        return VersionGroupPersistenceManager.refreshToHead(vg);        
    }
    
    public ModelBase retrieveVersionGroupModel(String refUuid) {
        VersionGroup vg = VersionGroupPersistenceManager.findByReferenceId(refUuid);
        if (vg == null) {
           throw new EJBException(String.format("retrieveVersionModel cannot find a VG with refUuid=%s", refUuid));
        }
        return vg.createUnionModel();        
    }
    
    
    public SystemInstance createInstance() {
        SystemInstance systemInstance = new SystemInstance();
        SystemInstancePersistenceManager.save(systemInstance);
        return systemInstance;
    }

    public void terminateInstance(String refUUID) {
        SystemInstance systemInstance = SystemInstancePersistenceManager.findByReferenceUUID(refUUID);
        if (systemInstance == null) {
            throw new EJBException(String.format("terminateInstance cannot find the SystemInstance with referenceUUID=%s", refUUID));
        }
        if (systemInstance.getSystemDelta() != null) {
            DeltaPersistenceManager.delete(systemInstance.getSystemDelta());
            if (systemInstance.getSystemDelta().getDriverSystemDeltas() != null) {
                for (Iterator<DriverSystemDelta> it = systemInstance.getSystemDelta().getDriverSystemDeltas().iterator(); it.hasNext();) {
                    DriverSystemDelta dsd = it.next();
                    DeltaPersistenceManager.delete(dsd);
                }
            }
        }
        SystemInstancePersistenceManager.delete(systemInstance);
    }
    
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void propagateDelta(SystemInstance systemInstance, SystemDelta sysDelta) {
        if (systemInstance.getSystemDelta() != null 
                && systemInstance.getSystemDelta().getDriverSystemDeltas() != null 
                && !systemInstance.getSystemDelta().getDriverSystemDeltas().isEmpty()) {
            throw new EJBException(String.format("Trying to propagateDelta for %s that has delta already progagated.", systemInstance));
        }
       // Note 1: a defaut VG (#1) must exist the first time the system starts.
        // Note 2: the VG below must contain versionItems for committed models only.
        VersionGroup referenceVG = sysDelta.getReferenceVersionGroup();
        if (referenceVG == null) {
            throw new EJBException(String.format("%s has no reference versionGroup to work with", systemInstance));
        }

        //EJBExeption may be thrown upon fault from subroutine of each step below
        //## Step 1. create reference model and target model 
        ModelBase referenceModel = referenceVG.createUnionModel();
        OntModel referenceOntModel = referenceModel.getOntModel();
        OntModel targetOntModel = referenceModel.dryrunDelta(sysDelta);

        //## Step 2. verify model change
        // 2.1. get head/lastest VG based on the current versionGroup 
        VersionGroup headVG = VersionGroupPersistenceManager.refreshToHead(referenceVG);
        // 2.2. if the head VG is newer than the current/reference VG
        if (headVG != null && !headVG.equals(referenceVG)) {
            //          create headOntModel. get D12=headModel.diffFromModel(refeneceOntModel)
            ModelBase headSystemModel = headVG.createUnionModel();
            // Note: The head model has committed (driverDeltas) or propagated (driverSystemDeltas if available) to reduce contention.
            // We must persist target model VG after both propagated and committed.
            DeltaBase D12 = headSystemModel.diffFromModel(referenceOntModel);
            //          verify D12.getModelAddition().getOntModel().intersection(sysDelta.getModelReduction().getOntModel()) == empty
            //          verify D12.getModelReduction().getOntModel().intersection(sysDelta.getModelAddiction().getOntModel()) == empty
            com.hp.hpl.jena.rdf.model.Model reductionConflict = D12.getModelAddition().getOntModel().intersection(sysDelta.getModelReduction().getOntModel());
            com.hp.hpl.jena.rdf.model.Model additionConflict = D12.getModelReduction().getOntModel().intersection(sysDelta.getModelAddition().getOntModel());
            //          if either verification fails throw EJBException("version conflict");
            if (!ModelUtil.isEmptyModel(reductionConflict) || !ModelUtil.isEmptyModel(additionConflict)) {
                throw new EJBException(String.format("%s %s based on %s conflicts with current head %s", systemInstance, sysDelta, referenceVG, headVG));
            }
            // Note: no need to update current VG to head as the targetDSD will be based the current VG and driverSystem will verify contention on its own.
        }
        //## Step 3. decompose sysDelta into driverSystemDeltas by <Topology>
        // 3.1. split targetOntModel to otain list of target driver topologies
        Map<String, OntModel> targetDriverSystemModels = ModelUtil.splitOntModelByTopology(targetOntModel);
        // 3.2. split referenceOntModel to otain list of reference driver topologies 
        Map<String, OntModel> referenceDriverSystemModels = ModelUtil.splitOntModelByTopology(referenceOntModel);
        // 3.3. create list of non-empty driverSystemDeltas by diff referenceOntModel components to targetOntModel
        List<DriverSystemDelta> targetDriverSystemDeltas = new ArrayList<>();
        for (String driverSystemTopoUri : targetDriverSystemModels.keySet()) {
            if (!referenceDriverSystemModels.containsKey(driverSystemTopoUri)) {
                throw new EJBException(String.format("%s cannot decompose %s due to unexpected target topology [uri=%s]", systemInstance, sysDelta, driverSystemTopoUri));
            }
            DriverInstance driverInstance = DriverInstancePersistenceManager.findByTopologyUri(driverSystemTopoUri);
            if (driverInstance == null) {
                throw new EJBException(String.format("%s cannot find driverInstance for target topology [uri=%s]", systemInstance, driverSystemTopoUri));
            }
            //get old versionItem for reference model
            VersionItem oldVI = referenceVG.getVersionItemByDriverInstance(driverInstance);
            OntModel tom = targetDriverSystemModels.get(driverSystemTopoUri);
            OntModel rom = referenceDriverSystemModels.get(driverSystemTopoUri);
            ModelBase referenceDSM = new ModelBase();
            referenceDSM.setOntModel(rom);
            // check diff from refrence model (tom) to target model (rom)
            DeltaBase delta = referenceDSM.diffToModel(tom);
            if (ModelUtil.isEmptyModel(delta.getModelAddition().getOntModel()) && ModelUtil.isEmptyModel(delta.getModelReduction().getOntModel())) {
                // no diff, use existing verionItem
                continue;
            }
            // create targetDSM and targetDSD only if there is a change. 
            ModelBase targetDSM = new ModelBase();
            targetDSM.setOntModel(tom);
            // create targetDSD 
            DriverSystemDelta targetDSD = new DriverSystemDelta();
            // do not save delta as it is transient but delta.modelA and delta.modelR must be saved
            targetDSD.setModelAddition(delta.getModelAddition());
            targetDSD.setModelReduction(delta.getModelReduction());
            targetDSD.setSystemDelta(sysDelta);
            // target delta uses version reference ID of committed model that corresponds to a known version in driverSystem.
            targetDSD.setReferenceVersionItem(oldVI);
            if (driverInstance == null) {
                throw new EJBException(String.format("%s cannot find a dirverInstance for topology: %s", systemInstance, driverSystemTopoUri));
            }
            // prepare to dispatch to driverInstance
            targetDSD.setDriverInstance(driverInstance);
            targetDriverSystemDeltas.add(targetDSD);
            // Save targetDSD modelA and modelR.
            targetDSD.getModelAddition().setDelta(null);
            targetDSD.getModelReduction().setDelta(null);
            ModelPersistenceManager.save(targetDSD.getModelAddition());
            ModelPersistenceManager.save(targetDSD.getModelReduction());
        }
        // Save systemDelta
        sysDelta.setDriverSystemDeltas(targetDriverSystemDeltas);
        sysDelta.setPersistent(false);
        DeltaPersistenceManager.save(sysDelta); // propogate to save included targetDriverSystemDeltas and modelA and modelR
        
        //## Step 4. propagate driverSystemDeltas 
        Context ejbCxt = null;
        for (DriverSystemDelta targetDSD : targetDriverSystemDeltas) {
            // save targetDSD
            DeltaPersistenceManager.save(targetDSD);
            targetDSD.getModelAddition().setDelta(targetDSD);
            targetDSD.getModelReduction().setDelta(targetDSD);
            ModelPersistenceManager.save(targetDSD.getModelAddition());
            ModelPersistenceManager.save(targetDSD.getModelReduction());
            // push driverSystemDeltas to driverInstances
            DriverInstance driverInstance = targetDSD.getDriverInstance();
            driverInstance.addDriverSystemDelta(targetDSD);
            String driverEjbPath = driverInstance.getDriverEjbPath();
            // make driverSystem propagateDelta call with targetDSD
            try {
                if (ejbCxt == null) {
                    ejbCxt = new InitialContext();
                }
                IHandleDriverSystemCall driverSystemHandler = (IHandleDriverSystemCall) ejbCxt.lookup(driverEjbPath);
                driverSystemHandler.propagateDelta(driverInstance, targetDSD);
            } catch (NamingException e) {
                throw new EJBException(e);
            }
        }
        // save systemInstance
        systemInstance.setSystemDelta(sysDelta);
        SystemInstancePersistenceManager.save(systemInstance);
        //## End of propgation
    }
    
    @Asynchronous
    public Future<String> commitDelta(SystemInstance systemInstance) {
        // 1. Get target VG from this stateful bean
        if (systemInstance.getSystemDelta() == null || systemInstance.getSystemDelta().getDriverSystemDeltas() == null 
                || systemInstance.getSystemDelta().getDriverSystemDeltas().isEmpty()) {
            throw new EJBException(String.format("%s has no systemDelta or driverSystemDeltas to commit", systemInstance));
        }
        Context ejbCxt = null;
        // 2. Get list of versionItem, driverInstances and DSD
        Map<DriverSystemDelta, Future<String>> commitResultMap = new HashMap<>();
        for (DriverSystemDelta dsd : systemInstance.getSystemDelta().getDriverSystemDeltas()) {
            DriverInstance driverInstance = dsd.getDriverInstance();
            if (driverInstance == null) {
                throw new EJBException(String.format("%s in %s has null driverInstance ", dsd, systemInstance));
            }
            try {
                if (ejbCxt == null) {
                    ejbCxt = new InitialContext();
                }
                String driverEjbPath = driverInstance.getDriverEjbPath();
                IHandleDriverSystemCall driverSystemHandler = (IHandleDriverSystemCall) ejbCxt.lookup(driverEjbPath);
                // 3. Call Async commitDelta to each driverInstance based on versionItems in VG.
                Future<String> result = driverSystemHandler.commitDelta(dsd);
                // 4. add AsyncResult to resultMap
                commitResultMap.put(dsd, result);
            } catch (NamingException e) {
                throw new EJBException(e);
            }
        }
        // 4. Qury for status in a loop bounded by timeout.
        //@TODO: make timeout and interval values configurable
        int timeoutMinutes = 10; // 10 minutes 
        for (int minute = 0; minute < timeoutMinutes; minute++) {
            boolean doneSucessful = true;
            for (DriverSystemDelta dsd : commitResultMap.keySet()) {
                Future<String> asyncResult = commitResultMap.get(dsd);
                if (!asyncResult.isDone()) {
                    doneSucessful = false;
                    break;
                }
                try {
                    String resultStatus = asyncResult.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new EJBException(String.format("commitDelta for %s raised exception from %s ", systemInstance, dsd.getDriverInstance()));
                }
            }
            if (doneSucessful) {
                return new AsyncResult<>("SUCCESS");
            }
            try {
                sleep(60000); // wait for 1 minute
            } catch (InterruptedException ex) {
                //Logger.getLogger(HandleSystemPushCall.class.getName()).log(Level.SEVERE, null, ex);
                throw new EJBException(String.format("commitDelta for %s is interrupted before timed out ", systemInstance));
            }
        }
        throw new EJBException(String.format("commitDelta for %s has timed out ", systemInstance));
    }
    
    
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void propagateDelta(String sysInstanceUUID, SystemDelta sysDelta) {
        SystemInstance systemInstance = SystemInstancePersistenceManager.findByReferenceUUID(sysInstanceUUID);
        if (systemInstance == null) {
            throw new EJBException("propagateDelta encounters unknown systemInstance with referenceUUID="+sysInstanceUUID);
        }
        
        this.propagateDelta(systemInstance, sysDelta);
    }
    
    @Asynchronous
    public Future<String> commitDelta(String sysInstanceUUID) {
        SystemInstance systemInstance = SystemInstancePersistenceManager.findByReferenceUUID(sysInstanceUUID);
        if (systemInstance == null) {
            throw new EJBException("commitDelta encounters unknown systemInstance with referenceUUID="+sysInstanceUUID);
        }
        
        return this.commitDelta(systemInstance);
    }
    
    
    public void plugDriverInstance(Map<String, String> properties) {
        if (!properties.containsKey("topologyUri") || !properties.containsKey("driverEjbPath")) {
           throw new EJBException(String.format("plugDriverInstance must provide both topologyUri and driverEjbPath properties"));
        }
        if (DriverInstancePersistenceManager.findByTopologyUri(properties.get("topologyUri")) != null) {
           throw new EJBException(String.format("A driverInstance has existed for topologyUri=%s", properties.get("topologyUri")));
        }
        DriverInstance newDI = new DriverInstance();
        newDI.setProperties(properties);
        newDI.setTopologyUri(properties.get("topologyUri"));
        newDI.setDriverEjbPath(properties.get("driverEjbPath"));
        DriverInstancePersistenceManager.save(newDI);
    }
    
    public void unplugDriverInstance(String topoUri) {
        DriverInstance di = DriverInstancePersistenceManager.findByTopologyUri(topoUri);
        if (di == null) {
           throw new EJBException(String.format("unplugDriverInstance cannot find the driverInstance for topologyUri=%s", topoUri));
        }
        // remove all related versionItems
        VersionItemPersistenceManager.deleteByDriverInstance(di);
        DriverInstancePersistenceManager.delete(di);
    }

    public DriverInstance retrieveDriverInstance(String topoUri) {
        DriverInstance di = DriverInstancePersistenceManager.findByTopologyUri(topoUri);
        if (di == null) {
           throw new EJBException(String.format("retrieveDriverInstance cannot find the driverInstance for topologyUri=%s", topoUri));
        }
        return di;
    }
    
    public Map<String, DriverInstance> retrieveAllDriverInstanceMap() {
        return DriverInstancePersistenceManager.getDriverInstanceByTopologyMap();
    }
}
