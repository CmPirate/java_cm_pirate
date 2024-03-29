package com.chengm.pirate.service.user;

import com.chengm.pirate.dao.UserBaseDao;
import com.chengm.pirate.pojo.UserBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * program: CmPirate
 * description:
 * author: ChengMo
 * create: 2019-12-08 16:51
 **/
@Service
public class UserBaseService {

    private UserBaseDao mDao;

    @Autowired
    public UserBaseService(UserBaseDao dao) {
        this.mDao = dao;
    }

    /**
     * 根据 uid 获取用户
     */
    public UserBase getUserBase(long uid) {
        return mDao.getUserBase(uid);
    }

    /**
     * 新增用户
     */
    public void insert(UserBase userBase) {
        mDao.insert(userBase);
    }

    /**
     * 删除用户
     */
    public void delete(long uid) {
        mDao.delete(uid);
    }

    /**
     * 更新用户信息
     */
    public void update(Map<String, Object> fields, long uid) {
        Map<String, Object> param = new HashMap<>();
        param.put("uid", uid);
        param.put("fields", fields);
        mDao.update(param);
    }

}
