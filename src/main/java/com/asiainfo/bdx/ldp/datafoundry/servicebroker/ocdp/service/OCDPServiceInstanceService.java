package com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.service;

import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.config.ClusterConfig;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.client.etcdClient;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.exception.OCDPServiceException;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.model.*;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.repository.OCDPServiceInstanceRepository;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.service.common.OCDPServiceInstanceCommonService;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.utils.BrokerUtil;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.utils.OCDPAdminServiceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.servicebroker.exception.ServiceBrokerInvalidParametersException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceExistsException;
import org.springframework.cloud.servicebroker.model.*;
import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.model.ServiceInstance;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.context.ApplicationContext;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Created by baikai on 7/23/16.
 */
@Service
public class OCDPServiceInstanceService implements ServiceInstanceService {

    private Logger logger = LoggerFactory.getLogger(OCDPServiceInstanceService.class);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OCDPServiceInstanceRepository repository;

    private ClusterConfig clusterConfig;

    // Operation response cache
    private Map<String, Future<CreateServiceInstanceResponse>> instanceProvisionStateMap;

    private Map<String, Future<DeleteServiceInstanceResponse>> instanceDeleteStateMap;

    private Map<String, Future<UpdateServiceInstanceResponse>> instanceUpdateStateMap;

    private LdapTemplate ldap;

    private etcdClient etcdClient;

    @Autowired
    public OCDPServiceInstanceService(ClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
        this.ldap = clusterConfig.getLdapTemplate();
        this.etcdClient = clusterConfig.getEtcdClient();
        this.instanceProvisionStateMap = new HashMap<>();
        this.instanceDeleteStateMap = new HashMap<>();
        this.instanceUpdateStateMap = new HashMap<>();
    }

    @Override
    public CreateServiceInstanceResponse createServiceInstance(
            CreateServiceInstanceRequest request) throws OCDPServiceException {
        	logger.info("Receiving create request: " + request);
            String serviceDefinitionId = request.getServiceDefinitionId();
            String serviceInstanceId = request.getServiceInstanceId();
            String planId = request.getPlanId();

            ServiceInstance instance = repository.findOne(serviceInstanceId);
            // Check service instance and planid
            if (instance != null) {
                logger.warn("Service instance with the given ID already exists: " + serviceInstanceId + ".");
                throw new ServiceInstanceExistsException(serviceInstanceId, serviceDefinitionId);
            }else if(! planId.equals(OCDPAdminServiceMapper.getOCDPServicePlan(serviceDefinitionId))){
                throw new ServiceBrokerInvalidParametersException("Unknown plan id: " + planId);
            }
         try {
            logger.info("Start to create OCDPServiceInstance: " + serviceInstanceId + "...");
            CreateServiceInstanceResponse response;
            OCDPServiceInstanceCommonService service = getOCDPServiceInstanceCommonService();
            if(request.isAsyncAccepted()){
                Future<CreateServiceInstanceResponse> responseFuture = service.doCreateServiceInstanceAsync(request);
                this.instanceProvisionStateMap.put(request.getServiceInstanceId(), responseFuture);
                //CITIC case: return service credential info in provision response body
                Map<String, Object> credential = service.getOCDPServiceCredential(serviceDefinitionId, serviceInstanceId);
                response = new OCDPCreateServiceInstanceResponse().withCredential(credential).withAsync(true);
            } else {
                response = service.doCreateServiceInstance(request);
            }
            return response;
		} catch (Exception e) {
			logger.error("Create service instance error: " + e.getMessage());
			throw new OCDPServiceException(e.getMessage());
		}

    }

    @Override
    public GetLastServiceOperationResponse getLastOperation(
            GetLastServiceOperationRequest request) throws OCDPServiceException {
    	try {
            logger.info("Receiving getLastOperation request: " + request);
            String serviceInstanceId = request.getServiceInstanceId();
            // Determine operation type: provision, delete or update
            OperationType operationType = getOperationType(serviceInstanceId);
            if (operationType == null){
                throw new OCDPServiceException("Service instance " + serviceInstanceId + " not exist.");
            }
            // Get Last operation response object from cache
            boolean is_operation_done = false;
            if( operationType == OperationType.PROVISION){
                Future<CreateServiceInstanceResponse> responseFuture = this.instanceProvisionStateMap.get(serviceInstanceId);
                is_operation_done = responseFuture.isDone();
            } else if( operationType == OperationType.DELETE){
                Future<DeleteServiceInstanceResponse> responseFuture = this.instanceDeleteStateMap.get(serviceInstanceId);
                is_operation_done = responseFuture.isDone();
            } else if ( operationType == OperationType.UPDATE){
                Future<UpdateServiceInstanceResponse> responseFuture = this.instanceUpdateStateMap.get(serviceInstanceId);
                is_operation_done = responseFuture.isDone();
            }
            // Return operation type
            if(is_operation_done){
                removeOperationState(serviceInstanceId, operationType);
                if (checkOperationResult(serviceInstanceId, operationType)){
                    return new GetLastServiceOperationResponse().withOperationState(OperationState.SUCCEEDED);
                } else {
                    return new GetLastServiceOperationResponse().withOperationState(OperationState.FAILED);
                }
            }else{
                return new GetLastServiceOperationResponse().withOperationState(OperationState.IN_PROGRESS);
            }
		} catch (Exception e) {
			logger.error("getLastOperation error: ", e);
			throw new RuntimeException(e);
		}

    }

    @Override
    public DeleteServiceInstanceResponse deleteServiceInstance(DeleteServiceInstanceRequest request)
            throws OCDPServiceException {
    	try {
            logger.info("Receiving delete request: " + request);
            String serviceInstanceId = request.getServiceInstanceId();
            logger.info("Receive request to delete service instance " + serviceInstanceId + " .");
            ServiceInstance instance = repository.findOne(serviceInstanceId);
            // Check service instance id
            if (instance == null) {
                throw new ServiceInstanceDoesNotExistException(serviceInstanceId);
            }
            logger.info("Start to delete OCDPServiceInstance: " + serviceInstanceId + "...");
            DeleteServiceInstanceResponse response;
            OCDPServiceInstanceCommonService service = getOCDPServiceInstanceCommonService();
            if(request.isAsyncAccepted()){
                Future<DeleteServiceInstanceResponse> responseFuture = service.doDeleteServiceInstanceAsync(
                        request, instance);
                this.instanceDeleteStateMap.put(request.getServiceInstanceId(), responseFuture);
                response = new DeleteServiceInstanceResponse().withAsync(true);
            } else {
                response = service.doDeleteServiceInstance(request, instance);
            }
            return response;
		} catch (Exception e) {
			logger.error("Delete ServiceInstance error: ", e);
			throw new RuntimeException(e);
		}

    }

    @Override
    public UpdateServiceInstanceResponse updateServiceInstance(UpdateServiceInstanceRequest request)
            throws OCDPServiceException {
    	try {
            String serviceInstanceId = request.getServiceInstanceId();
            Map<String, Object> params = request.getParameters();
            logger.info("Receiving update request: " + request);
            String userName = (String)params.get("user_name");
            ServiceInstance instance = repository.findOne(serviceInstanceId);
            // Check service instance id
            if (instance == null) {
                throw new ServiceInstanceDoesNotExistException(serviceInstanceId);
            }
            String password;
            if(! BrokerUtil.isLDAPUserExist(ldap, userName)){
                password = UUID.randomUUID().toString();
            }else {
                password = etcdClient.readToString(
                        "/servicebroker/ocdp/user/krbinfo/" + userName + "@" + clusterConfig.getKrbRealm() + "/password");
                // Generate password for exist ldap user if krb password are missing
                if (password == null){
                    password = UUID.randomUUID().toString();
                    etcdClient.write(
                            "/servicebroker/ocdp/user/krbinfo/" + userName + "@" + clusterConfig.getKrbRealm() + "/password", password);
                }
            }
            UpdateServiceInstanceResponse response;
            OCDPServiceInstanceCommonService service = getOCDPServiceInstanceCommonService();
            if (request.isAsyncAccepted()){
                Future<UpdateServiceInstanceResponse> responseFuture = service.doUpdateServiceInstanceAsync(
                        request, instance, password);
                this.instanceUpdateStateMap.put(request.getServiceInstanceId(), responseFuture);
                response = new OCDPUpdateServiceInstanceResponse().withAsync(true);
            }else {
                response = service.doUpdateServiceInstance(request, instance, password);
            }
            return response;
		} catch (Exception e) {
			logger.error("Update ServiceInstance error: ", e);
			throw new RuntimeException(e);
		}

    }

    private OCDPServiceInstanceCommonService getOCDPServiceInstanceCommonService() {
        return (OCDPServiceInstanceCommonService)context.getBean("OCDPServiceInstanceCommonService");
    }

    private OperationType getOperationType(String serviceInstanceId){
        if (this.instanceProvisionStateMap.get(serviceInstanceId) != null){
            return OperationType.PROVISION;
        } else if (this.instanceDeleteStateMap.get(serviceInstanceId) != null){
            return OperationType.DELETE;
        } else if (this.instanceUpdateStateMap.get(serviceInstanceId) != null){
            return OperationType.UPDATE;
        } else {
            return null;
        }
    }

    private boolean checkOperationResult(String serviceInstanceId, OperationType operationType){
        if (operationType == OperationType.PROVISION){
            // For instance provision case, return true if instance information existed in etcd
            return (repository.findOne(serviceInstanceId) != null);
        }else if(operationType == OperationType.DELETE){
            // For instance delete case, return true if instance information not existed in etcd
            return (repository.findOne(serviceInstanceId) == null);
        } else if (operationType == OperationType.UPDATE) {
            // Temp solution: For instance update case, just return true if update operation is done
            // Need a better solution in future to determine update operation is fail or success
            return true;
        } else {
            return false;
        }
    }

    private void removeOperationState(String serviceInstanceId, OperationType operationType){
        if (operationType == OperationType.PROVISION){
            this.instanceProvisionStateMap.remove(serviceInstanceId);
        } else if ( operationType == OperationType.DELETE){
            this.instanceDeleteStateMap.remove(serviceInstanceId);
        } else if ( operationType == OperationType.UPDATE){
            this.instanceUpdateStateMap.remove(serviceInstanceId);
        }
    }
}
