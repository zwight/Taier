package com.dtstack.rdos.engine.execution.base.plugin.log;

import java.util.Collection;

/**
 * Created by sishu.yss on 2018/4/17.
 */
public abstract class LogStore {

    public abstract  int insert(String jobId, String jobInfo, int status);

    public abstract  int updateStatus(String jobId, int status);

    public abstract  void updateModifyTime(Collection<String> jobIds);

    public abstract void updateErrorLog(String jobId, String errorLog);

    public abstract Integer getStatusByJobId(String jobId);

    public abstract String getLogByJobId(String jobId);

    public abstract void timeOutDeal();

    public abstract void clearJob();

    }
