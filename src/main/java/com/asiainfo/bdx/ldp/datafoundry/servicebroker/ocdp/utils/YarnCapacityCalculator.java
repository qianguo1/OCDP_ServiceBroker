package com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.utils;

import com.asiainfo.bdx.ldp.datafoundry.servicebroker.ocdp.model.CapacitySchedulerConfig;
import com.google.common.base.Splitter;

import java.util.Map;
import java.util.UUID;

/**
 * Created by Aaron on 16/7/26.
 */
public class YarnCapacityCalculator {

//    private String planId;

//    private String serviceInstanceId;

//    private CapacitySchedulerConfig csConfig;
    private Map<String,String> properties;
    private String allQueues;
    private Double totalMemory;
    private Double availableCapacity;


    public YarnCapacityCalculator(String totalMem, CapacitySchedulerConfig csConfig){

//        this.planId = planId;
//        this.serviceInstanceId = serviceInstanceId;
//        this.csConfig = csConfig;
        this.properties = csConfig.getItems().get(0).getProperties();
        this.totalMemory = Double.parseDouble(totalMem);
        this.availableCapacity = Double.parseDouble(properties.get("yarn.scheduler.capacity.root.default.capacity"));
    }

    public Map<String,String> getProperties(){ return properties;}

    /**
     * A Method to apply queue with capacity quota
     * @param quota
     */
    public String applyQueue(Long quota){

        String emptyQueue = null;
        //To make sure that the sum of all the queues capacity equals 100%, the patch for ambari server to support
        // two decimal places in capacity scheduler should be installed.
        String targetQueueCapacity = String.format("%.2f", ((100 * quota) / (totalMemory / 1024)));
        String resourcePoolCapacity = String.format("%.2f", (availableCapacity - (Double.parseDouble(targetQueueCapacity))));

        if(Double.parseDouble(targetQueueCapacity) > availableCapacity){
//            throw new OCDPServiceException("Not Enough Capacity to apply!");
            return null;
        }
        emptyQueue = getFirstEmptyQueue();

        if(emptyQueue != null) {
            properties.replace("yarn.scheduler.capacity.root."+emptyQueue+".capacity","0", targetQueueCapacity);
            properties.replace("yarn.scheduler.capacity.root."+emptyQueue+".maximum-capacity","0",targetQueueCapacity);
        }
        else {
            String newQueue = UUID.randomUUID().toString();
            emptyQueue = newQueue;
            properties.replace("yarn.scheduler.capacity.root.queues",allQueues,allQueues+","+newQueue);
            properties.put("yarn.scheduler.capacity.root."+newQueue+".capacity",targetQueueCapacity);
            properties.put("yarn.scheduler.capacity.root."+newQueue+".maximum-capacity",targetQueueCapacity);
        }
        properties.replace("yarn.scheduler.capacity.root.default.capacity",resourcePoolCapacity);
        properties.replace("yarn.scheduler.capacity.root.default.maximum-capacity",resourcePoolCapacity);

        return emptyQueue;

    }

    public void updateQueue(String queueName, Long quota) {
        // Re-calculate new capacity
        String originQueueCapacity = properties.get("yarn.scheduler.capacity."+
                queueName+".capacity");
        String targetQueueCapacity = String.format("%.2f", ((100 * quota) / (totalMemory / 1024)));
        String resourcePoolCapacity = String.format("%.2f", (availableCapacity - (Double.parseDouble(targetQueueCapacity) - Double.parseDouble(originQueueCapacity))));
        // Update queue
        properties.replace("yarn.scheduler.capacity."+queueName+".capacity", targetQueueCapacity);
        properties.replace("yarn.scheduler.capacity."+queueName+".maximum-capacity",targetQueueCapacity);
        // Update default queue
        properties.replace("yarn.scheduler.capacity.root.default.capacity",resourcePoolCapacity);
        properties.replace("yarn.scheduler.capacity.root.default.maximum-capacity",resourcePoolCapacity);
    }

    /**
     * remoke capacity to zero
     */
    public boolean revokeQueue(String serviceInstanceResuorceName){

        String targetQueueCapacity = properties.get("yarn.scheduler.capacity."+
                serviceInstanceResuorceName+".capacity");
        String resourcePoolCapacity = String.valueOf(availableCapacity+Double.parseDouble(targetQueueCapacity));
        if(targetQueueCapacity == null)
            return false;
        else {
            properties.replace("yarn.scheduler.capacity."+serviceInstanceResuorceName+".capacity"
                    ,targetQueueCapacity,"0");
            properties.replace("yarn.scheduler.capacity."+serviceInstanceResuorceName+".maximum-capacity"
            ,targetQueueCapacity,"0");
            properties.replace("yarn.scheduler.capacity.root.default.capacity",resourcePoolCapacity);
            properties.replace("yarn.scheduler.capacity.root.default.maximum-capacity",resourcePoolCapacity);
        }
        return true;
    }

    private String getFirstEmptyQueue(){

//        ArrayList<String> queues = new ArrayList<String>();
//        String emptyQueue = null;
        String queuesStr = properties.get("yarn.scheduler.capacity.root.queues");
        this.allQueues = queuesStr;
        for(String queue : Splitter.on(",").split(queuesStr))
        {
//            queues.add(queue);
            if(properties.get("yarn.scheduler.capacity.root."+queue+".capacity").equals("0")
                    &&properties.get("yarn.scheduler.capacity.root."+queue+".maximum-capacity").equals("0"))
            {
                return queue;
            }
        }

        return null;

    }

    public String removeQueueMapping(String user, String queue){
        String absoluteQueue = queue.substring(5);
        String newQueueMapStr = "";
        String queueMapStr = this.properties.get("yarn.scheduler.capacity.queue-mappings");
        if(queueMapStr == null)
            return null;
        for(String queueMap : Splitter.on(",").split(queueMapStr))
        {
            if(queueMap.startsWith("u")&&queueMap.endsWith(absoluteQueue)){
                if(queueMap.contains(user)){
                    continue;
                }
            }
            newQueueMapStr += queueMap;
            newQueueMapStr += ",";
        }
        if(!newQueueMapStr.equals("")){
            newQueueMapStr = newQueueMapStr.substring(0,newQueueMapStr.length()-1);
            properties.replace("yarn.scheduler.capacity.queue-mappings",queueMapStr,newQueueMapStr);
        }else{
            properties.replace("yarn.scheduler.capacity.queue-mappings",queueMapStr,"");
        }
        return newQueueMapStr;

    }

    public String removeQueueMapping(String queue){
        String absoluteQueue = queue.substring(5);
        String newQueueMapStr = "";
        String queueMapStr = this.properties.get("yarn.scheduler.capacity.queue-mappings");
        if(queueMapStr == null)
            return null;
        for(String queueMap : Splitter.on(",").split(queueMapStr))
        {
            if(queueMap.startsWith("u")&&queueMap.endsWith(absoluteQueue)){
                continue;
            }
            newQueueMapStr += queueMap;
            newQueueMapStr += ",";
        }
        if(!newQueueMapStr.equals("")){
            newQueueMapStr = newQueueMapStr.substring(0,newQueueMapStr.length()-1);
            properties.replace("yarn.scheduler.capacity.queue-mappings",queueMapStr,newQueueMapStr);
        }else{
            properties.replace("yarn.scheduler.capacity.queue-mappings",queueMapStr,"");
        }
        return newQueueMapStr;
    }


    public String addQueueMapping(String user, String queue){
        String absoluteQueueName = queue.substring(5);
        String newQueueMapStr = null;
        String queueMapStr = this.properties.get("yarn.scheduler.capacity.queue-mappings");

        if(queueMapStr == null||queueMapStr.equals(""))
            newQueueMapStr = "u:"+user+":"+absoluteQueueName;
        else
            newQueueMapStr = queueMapStr + ",u:" +user+ ":" +absoluteQueueName;

        properties.put("yarn.scheduler.capacity.queue-mappings",newQueueMapStr);

        return newQueueMapStr;
    }





}
