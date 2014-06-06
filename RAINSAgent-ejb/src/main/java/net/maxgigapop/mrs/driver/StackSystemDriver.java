/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package net.maxgigapop.mrs.driver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.AsyncResult;
import javax.ejb.Asynchronous;
import javax.ejb.EJBException;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import net.maxgigapop.mrs.bean.DriverInstance;
import net.maxgigapop.mrs.bean.DriverModel;
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
import net.maxgigapop.mrs.bean.persist.VersionItemPersistenceManager;
import net.maxgigapop.mrs.system.HandleSystemCall;
import net.maxgigapop.mrs.system.HandleSystemPushCall;
import net.maxgigapop.mrs.system.IHandleDriverSystemCall;

/**
 *
 * @author xyang
 */

//use properties: driverSystemPath which translates to calls in driverSystemEjbPath.HandleSystemPushCall and driverSystemEjbPath.HandleSystemCall

@Stateless
public class StackSystemDriver implements IHandleDriverSystemCall{
    private static Map<VersionItem, HandleSystemPushCall> driverSystemSessionMap = new HashMap<VersionItem, HandleSystemPushCall>();
            
    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void propagateDelta(DriverInstance driverInstance, DriverSystemDelta aDelta) {
        driverInstance = DriverInstancePersistenceManager.findById(driverInstance.getId());
        aDelta = (DriverSystemDelta)DeltaPersistenceManager.findById(aDelta.getId());
        String driverSystemEjbBase = driverInstance.getProperty("driverSystemPath");
        if (driverSystemEjbBase == null) {
            throw new EJBException(String.format("%s has no property key=driverSystemPath"));
        }
        String ejbPathHandleSystemCall = driverSystemEjbBase + "HandleSystemCall";
        String ejbPathHandleSystemPushCall = driverSystemEjbBase + "HandleSystemPushCall";
        try {
            Context ejbCxt = new InitialContext();
            HandleSystemCall ejbSystemHandler = (HandleSystemCall)ejbCxt.lookup(ejbPathHandleSystemCall);
            HandleSystemPushCall ejbSystemPushHandler = (HandleSystemPushCall)ejbCxt.lookup(ejbPathHandleSystemPushCall);
            SystemInstance si = ejbSystemPushHandler.createInstance();
            VersionGroup vg = ejbSystemHandler.updateHeadVersionGroup(aDelta.getReferenceVersionItem().getReferenceId());
            SystemDelta sysDelta = new SystemDelta();
            sysDelta.setSystemInstance(si);
            sysDelta.setReferenceVersionGroup(vg);
            sysDelta.setModelAddition(aDelta.getModelAddition());
            sysDelta.setModelReduction(aDelta.getModelReduction());
            ejbSystemPushHandler.propagateDelta(sysDelta);
            driverSystemSessionMap.put(aDelta.getTargetVersionItem(), ejbSystemPushHandler);
        } catch (Exception e) {
            throw new EJBException(String.format("propagateDelta failed for %s with %s due to exception (%s)", driverInstance, aDelta, e.getMessage()));
        }
    }

    @Override
    @Asynchronous
    public Future<String> commitDelta(Long driverInstanceId, Long targetVIId) {
        DriverInstance driverInstance = DriverInstancePersistenceManager.findById(driverInstanceId);
        if (driverInstance == null) {
            throw new EJBException(String.format("commitDelta cannot find driverInance(id=%d)", driverInstanceId));
        }
        VersionItem targetVI = (VersionItem)VersionItemPersistenceManager.findById(targetVIId);
        if (targetVI == null) {
            throw new EJBException(String.format("commitDelta cannot find target VersionItem(id=%d)", targetVIId));
        }
        if (!driverSystemSessionMap.containsKey(targetVI)) {
            throw new EJBException(String.format("commitDelta cannot associate %s with a driverSystemSession", targetVI));
        }
        try {
            HandleSystemPushCall ejbSystemPushHandler = driverSystemSessionMap.get(targetVI);
            ejbSystemPushHandler.commitDelta();
        } catch (Exception e) {
            throw new EJBException(String.format("commitDelta cannot find target VersionItem(id=%d)", targetVIId));
        }
        return new AsyncResult<String>("SUCCESS");
    }
    // TODO: terminate or reuse sessions in driverSystemSessionMap after commit
    
    @Override
    @Asynchronous
    public Future<String> pullModel(Long driverInstanceId) {
        DriverInstance driverInstance = DriverInstancePersistenceManager.findById(driverInstanceId);
        if (driverInstance == null) {
            throw new EJBException(String.format("pullModel cannot find driverInance(id=%d)", driverInstanceId));
        }
        String driverSystemEjbBase = driverInstance.getProperty("driverSystemPath");
        if (driverSystemEjbBase == null) {
            throw new EJBException(String.format("%s has no driverSystemPath property configured", driverInstance));
        }
        String ejbPathHandleSystemCall = driverSystemEjbBase + "HandleSystemCall";
        VersionItem vi = null;
        DriverModel dm = null;
        try {
            Context ejbCxt = new InitialContext();
            HandleSystemCall ejbSystemHandler = (HandleSystemCall)ejbCxt.lookup(ejbPathHandleSystemCall);
            vi = new VersionItem();
            vi.setDriverInstance(driverInstance);
            VersionItemPersistenceManager.save(vi);
            VersionGroup vg = ejbSystemHandler.createHeadVersionGroup(vi.getId());
            vi.setReferenceId(vg.getId());
            ModelBase model = ejbSystemHandler.retrieveVersionGroupModel(vg.getReferenceId());
            dm = new DriverModel();
            dm.setCommitted(true);
            dm.setOntModel(model.getOntModel());
            ModelPersistenceManager.save(dm);
            vi.setModelRef(dm);
            VersionItemPersistenceManager.save(vi);
        } catch (Exception e) {
            try {
                if (dm != null) {
                    ModelPersistenceManager.delete(dm);
                }
                if (vi != null) {
                    VersionItemPersistenceManager.delete(vi);
                }
            } catch (Exception ex) {
                ; // do nothing (loggin?)
            }
            throw new EJBException(String.format("pullModel on %s raised exception[%s]", driverInstance, e.getMessage()));
        }
        return new AsyncResult<String>("SUCCESS");
    }
    
    public void pullModelSync(DriverInstance driverInstance) {
        
    }
}