package com.dtstack.rdos.engine.entrance.db.dao;

import org.apache.ibatis.session.SqlSession;

import com.dtstack.rdos.engine.entrance.db.callback.MybatisSessionCallback;
import com.dtstack.rdos.engine.entrance.db.callback.MybatisSessionCallbackMethod;
import com.dtstack.rdos.engine.entrance.db.dataobject.RdosNodeMachine;
import com.dtstack.rdos.engine.entrance.db.mapper.RdosNodeMachineMapper;

public class RdosNodeMachineDAO {
	
	public void insert(String ip,long port,byte machineType){
		final RdosNodeMachine RdosNodeMachine = new RdosNodeMachine(ip,port,machineType);
		MybatisSessionCallbackMethod.doCallback(new MybatisSessionCallback(){
			@Override
			public void execute(SqlSession sqlSession) throws Exception {
				// TODO Auto-generated method stub
				RdosNodeMachineMapper rdosNodeMachineMapper = sqlSession.getMapper(RdosNodeMachineMapper.class);
				rdosNodeMachineMapper.insert(RdosNodeMachine);
			}
		});
	}
	
	public void deleteUnavaiableMasterNode(){
		MybatisSessionCallbackMethod.doCallback(new MybatisSessionCallback(){
			@Override
			public void execute(SqlSession sqlSession) throws Exception {
				// TODO Auto-generated method stub
				RdosNodeMachineMapper rdosNodeMachineMapper = sqlSession.getMapper(RdosNodeMachineMapper.class);
				rdosNodeMachineMapper.deleteUnavaiableMasterNode();
			}
		});
	}
}