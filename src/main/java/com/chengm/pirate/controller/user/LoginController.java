package com.chengm.pirate.controller.user;

import com.chengm.pirate.base.impl.BaseBizController;
import com.chengm.pirate.config.AppConfig;
import com.chengm.pirate.entity.AjaxResult;
import com.chengm.pirate.pojo.UserBase;
import com.chengm.pirate.pojo.UserExtra;
import com.chengm.pirate.service.mail.EmailService;
import com.chengm.pirate.service.user.UserBaseService;
import com.chengm.pirate.service.user.UserExtraService;
import com.chengm.pirate.utils.*;
import com.chengm.pirate.utils.constant.*;
import com.chengm.pirate.utils.log.LogLevel;
import com.chengm.pirate.utils.log.LogUtil;
import com.chengm.pirate.pojo.UserAuth;
import com.chengm.pirate.service.user.UserAuthService;
import com.chengm.pirate.utils.ids.IdGenerator;
import com.chengm.pirate.utils.token.TokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * program: CmPirate
 * description: 登录相关操作
 * author: ChengMo
 * create: 2019-12-01 10:20
 **/
@RestController
public class LoginController extends BaseBizController {

    private UserAuthService mUserAuthService;
    private UserExtraService mUserExtraService;
    private UserBaseService mUserBaseService;
    private EmailService mEmailService;

    @Autowired
    public LoginController(UserAuthService service1, UserExtraService service2, UserBaseService service3, EmailService service4) {
        this.mUserAuthService = service1;
        this.mUserExtraService = service2;
        this.mUserBaseService = service3;
        this.mEmailService = service4;
    }

    /**
     * 用户使用密码登录
     */
    @RequestMapping("/user/accountLogin")
    public AjaxResult accountLogin() {

        // 校验请求方式
        requestMethodPost();

        /*
         * 需要的参数 identity_type  identifier password
         */
        int identityType = requireIntParam("identityType");
        String identifier = requireStringParam("identifier");
        String password = requireStringParam("password");

        /*
         * 需要根据 identityType 来验证此参数
         * 1 手机号  2 微信  3 QQ  4 邮箱
         */
        if (identityType == IdentityType.IDENTITY_TYPE_PHONE) {
            if (!VerifyUtil.isPhone(identifier)) {
                return AjaxResult.fail("手机号码格式不正确");
            }
        } else if (identityType == IdentityType.IDENTITY_TYPE_EMAIL) {
            if (!VerifyUtil.isEmail(identifier)) {
                return AjaxResult.fail("邮箱格式不正确");
            }
        } else {
            return AjaxResult.failInvalidParameter("identityType");
        }

        // 到数据库中查询是否已经存在此用户
        UserAuth userAuth = mUserAuthService.getUser(identifier);
        if (userAuth != null) {
            // 限定用户密码输入错误次数
            Integer errorCount = (Integer) mRedisUtil.get(RedisKeyUtils.getPwdInputCountKey(identifier));
            if (errorCount == null) {
                errorCount = 0;
            }
            if (errorCount > AppConfig.USER_PWD_LIMIT_COUNT) {
                return AjaxResult.failAccountLimit();
            }

            // 验证密码的正确性, 前端需要对密码d5加密传输
            if (!userAuth.getPassword().equals(password)) {
                String failTip = "密码错误";
                int temp = AppConfig.USER_PWD_LIMIT_COUNT - errorCount;
                if (temp <= 3 && temp > 0) {
                    failTip = failTip + "，您还可以输入" + temp + "次";
                } else if (temp == 0) {
                    failTip = failTip + "，您的账号密码登录方式已被锁定";
                }
                errorCount++;
                mRedisUtil.set(RedisKeyUtils.getPwdInputCountKey(identifier), errorCount, AppConfig.ACCOUNT_LIMIT_TIME);
                return AjaxResult.fail(failTip);
            } else {
                // 登录成功即删除错误限制记录, 需要重新计算
                mRedisUtil.del(RedisKeyUtils.getPwdInputCountKey(identifier));
            }

            // 用户存在则执行登录操作
            userAuth = userLogin(userAuth);
        } else {
            return AjaxResult.failUserNotExist();
        }

        // 验证 deviceId
        long uid = userAuth.getUid();
        UserExtra userExtra = mUserExtraService.getUserExtra(uid);
        if (userExtra == null) {
            return AjaxResult.fail(CodeConstants.ACCOUNT_EXCEPTION, "账号异常，请联系管理员");
        }
        String loginDeviceId = userExtra.getDeviceId();
        if (DeviceUtils.isNeedDeviceVerity(getDeviceId(), loginDeviceId)) {
            return AjaxResult.fail(CodeConstants.USER_NEW_DEVICE, "在新的设备登陆，需要先验证");
        }
//        if (!StringUtil.isEmpty(loginDeviceId)) {
//            String[] dIds = loginDeviceId.split(",");
//            List<String> ids = Arrays.asList(dIds);
//            if (!ids.contains(getDeviceId())) {
//                return AjaxResult.fail(CodeConstants.USER_NEW_DEVICE, "在新的设备登陆，需要先验证");
//            }
//        }

        // 更新用户扩展信息
        updateUserExtra(uid);

        // 将数据返回
        return AjaxResult.success(getResultMap(userAuth));
    }

    /**
     * 用户使用验证码登录
     */
    @RequestMapping("/user/codeLogin")
    public AjaxResult codeLogin() {
        /*
         * 需要的参数
         * identity_type  identifier code
         */
        int identityType = requireIntParam("identityType");
        String identifier = requireStringParam("identifier");
        String code = requireStringParam("code");

        // 客户端验证， 目前支持 ANDROID IOS
        if (!VerifyUtil.isClient(getOsName())) {
            return AjaxResult.failInvalidParameter("osName");
        }

        /*
         * 需要根据 identityType 来验证此参数
         * 1 手机号  2 微信  3 QQ  4 邮箱
         */
        if (identityType == IdentityType.IDENTITY_TYPE_PHONE) {
            if (!VerifyUtil.isPhone(identifier)) {
                return AjaxResult.fail("手机号码格式不正确");
            }
        } else if (identityType == IdentityType.IDENTITY_TYPE_EMAIL) {
            if (!VerifyUtil.isEmail(identifier)) {
                return AjaxResult.fail("邮箱格式不正确");
            }
        } else {
            return AjaxResult.failInvalidParameter("identityType");
        }

        /*
         * 验证码
         * 目前未接入短信验证码，邮箱验证码需要验证
         */
        if (StringUtil.getLength(code) == Constants.VERIFICATION_CODE_LENGTH) {
            if (identityType == IdentityType.IDENTITY_TYPE_EMAIL) {
                int codeStatus = mEmailService.checkRegisterEmailCode(identifier, code);
                if (codeStatus == CodeStatus.CODE_EXPIRE) {
                    return AjaxResult.fail(CodeConstants.PARAM_ERROR, "验证码已过期，请重新获取");
                } else if (codeStatus == CodeStatus.CODE_FAIL) {
                    return AjaxResult.fail(CodeConstants.PARAM_ERROR, "验证码错误");
                }
            } else {
                LogUtil.logValue("CODE", "目前未接入相关短信验证码平台", LogLevel.LEVEL_WARN);
            }
        } else {
            return AjaxResult.fail(CodeConstants.PARAM_ERROR, "验证码错误");
        }

        // 到数据库中查询是否已经存在此用户
        UserAuth userAuth = mUserAuthService.getUser(identifier);
        if (userAuth != null) {
            // 用户存在则执行登录操作
            userAuth = userLogin(userAuth);

            // 登录成功即删除错误限制记录, 需要重新计算
            mRedisUtil.del(RedisKeyUtils.getPwdInputCountKey(userAuth.getIdentifier()));
        } else {
            // 用户不存在则创建新用户执行注册操作
            UserAuth ua = new UserAuth();
            ua.setIdentityType(identityType);
            ua.setIdentifier(identifier);
            userAuth = userRegister(ua);

            // 更新用户基础信息
            initUserBase(userAuth.getUid(), identityType, identifier, userAuth.getToken());
        }

        // 更新用户扩展信息
        long uid = userAuth.getUid();
        updateUserExtra(uid);

        // 将数据返回
        return AjaxResult.success(getResultMap(userAuth));
    }

    /**
     * 第三方账号登陆，目前支持微信、QQ
     */
    @RequestMapping("/user/thirdLogin")
    public AjaxResult thirdLogin() {
        int identityType = requireIntParam("identityType");
        String identifier = requireStringParam("identifier");// 微信号或者QQ,账号的唯一标识
        String face = getStringParam("face");
        String city = getStringParam("city");
        String userName = getStringParam("userName");
        int gender = getIntParam("gender");
        long birthday = getLongParam("birthday");
        String signature = getStringParam("signature");

        if (identityType != IdentityType.IDENTITY_TYPE_WECHAT && identityType != IdentityType.IDENTITY_TYPE_QQ) {
            return AjaxResult.failInvalidParameter("identityType");
        }

        // 到数据库中查询是否已经存在此用户
        UserAuth userAuth = mUserAuthService.getUser(identifier);
        if (userAuth != null) {

            // 用户存在则执行登录操作
            userAuth = userLogin(userAuth);
        } else {
            // 用户不存在则创建新用户执行注册操作
            UserAuth ua = new UserAuth();
            ua.setIdentityType(identityType);
            ua.setIdentifier(identifier);
            userAuth = userRegister(ua);

            // 更新用户基础信息
            initUserBase(userAuth.getUid(), identityType, identifier, userAuth.getToken(),
                    face, city, userName, gender, birthday, signature);
        }

        // 更新用户扩展信息
        updateUserExtra(userAuth.getUid());

        // 将数据返回
        return AjaxResult.success(getResultMap(userAuth));
    }

    // 执行用户登录成功操作，更新用户登录信息
    private UserAuth userLogin(UserAuth userAuth) {
        if (userAuth == null) {
            return null;
        }
        // 需要重新生成token
        userAuth.setToken(TokenUtil.sign(userAuth.getUid()));
        mUserAuthService.updateUserToken(userAuth);

        return userAuth;
    }

    // 用户注册, 在数据库中生成一个用户对象并将用户信息返回
    private UserAuth userRegister(UserAuth userAuth) {
        // 生成uid
        userAuth.setUid(IdGenerator.getDefault().nextId());
        // 给用户生成一个默认密码
        userAuth.setPassword(MD5Util.getMD5DefaultPwd());
        // 生成token
        userAuth.setToken(TokenUtil.sign(userAuth.getUid()));

        // 将数据插入数据库中
        mUserAuthService.insert(userAuth);

        return userAuth;
    }

    // 登陆成功时更新用户扩展信息
    private void updateUserExtra(long uid) {
        UserExtra userExtra = mUserExtraService.getUserExtra(uid);
        if (userExtra == null) {
            userExtra = new UserExtra();
            setUserExtra(userExtra, uid, getDeviceId(), getOsName(),
                    getOsVersion(), getClientVersion(), getVendor(), getDeviceName(), getIdfa(), getIdfv());
            mUserExtraService.insert(userExtra);
        } else {
            setUserExtra(userExtra, uid, getDeviceId(), getOsName(),
                    getOsVersion(), getClientVersion(), getVendor(), getDeviceName(), getIdfa(), getIdfv());
            mUserExtraService.update(userExtra);
        }
    }

    private void setUserExtra(UserExtra userExtra, long uid, String deviceId, String osName, String osVersion,
                               String clientVersion, String vendor, String deviceName, String idfa, String idfv) {
        if (uid != 0) {
            userExtra.setUid(uid);
        }

        // 最多保存 5 个deviceId
        if (!StringUtil.isEmpty(userExtra.getDeviceId())) {
            deviceId = DeviceUtils.addUserDeviceId(userExtra.getDeviceId(), deviceId);;
        }
        userExtra.setDeviceId(deviceId);
        userExtra.setOsName(osName);
        userExtra.setOsVersion(osVersion);
        userExtra.setClientVersion(clientVersion);
        userExtra.setVendor(vendor);
        userExtra.setDeviceName(deviceName);
        if (osName.equals(Constants.CLIENT_IOS)) {
            userExtra.setIdfa(idfa);
            userExtra.setIdfv(idfv);
        } else {
            userExtra.setIdfa("");
            userExtra.setIdfv("");
        }
        userExtra.setClientName("");
        userExtra.setExtend1("");
        userExtra.setExtend2("");
        userExtra.setExtend3("");
    }

    // 设置用户基础信息
    private void initUserBase(long uid, int identityType, String identifier, String pushToken) {
        UserBase userBase = mUserBaseService.getUserBase(uid);
        if (userBase == null) {
            userBase = new UserBase();
            userBase.setUid(uid);
            userBase.setRegisterSource(identityType);
            if (identityType == IdentityType.IDENTITY_TYPE_PHONE) {
                userBase.setMobile(identifier);
                userBase.setMobileBindTime(System.currentTimeMillis());
            } else if (identityType == IdentityType.IDENTITY_TYPE_EMAIL) {
                userBase.setEmail(identifier);
                userBase.setEmailBindTime(System.currentTimeMillis());
            }
            // 新注册用户都是正常用户
            userBase.setUserRole(Role.ROLE_NORMAL);
            userBase.setPushToken(pushToken);
            userBase.setUserName(identifier);
            mUserBaseService.insert(userBase);
        }
    }

    // 设置用户基础信息
    private void initUserBase(long uid, int identityType, String identifier, String pushToken, String face,
                              String city, String userName, int gender, long birthday, String signature) {
        UserBase userBase = mUserBaseService.getUserBase(uid);
        if (userBase == null) {
            userBase = new UserBase();
            userBase.setUid(uid);
            userBase.setRegisterSource(identityType);
            if (!StringUtil.isEmpty(face)) {
                userBase.setFace(face);
            }
            if (!StringUtil.isEmpty(userName)) {
                userBase.setUserName(userName);
            }
            if (!StringUtil.isEmpty(signature)) {
                userBase.setSignature(signature);
            }
            if (!StringUtil.isEmpty(city)) {
                userBase.setCity(city);
            }
            userBase.setGender(gender);
            userBase.setBirthday(birthday);

            // 新注册用户都是正常用户
            userBase.setUserRole(Role.ROLE_NORMAL);
            userBase.setPushToken(pushToken);
            userBase.setUserName(identifier);
            mUserBaseService.insert(userBase);
        }
    }

    private Map<String, Object> getResultMap(UserAuth userAuth) {
        // token放头部返回
        response.addHeader("token", userAuth.getToken());

        // 查询用户基础信息返回
        UserBase userBase = mUserBaseService.getUserBase(userAuth.getUid());
        Map<String, Object> result = new HashMap<>();
        if (userBase == null) {
            result.put("uid", userAuth.getUid());
            result.put("userName", userAuth.getIdentifier());
        } else {
            result.put("uid", userBase.getUid());
            result.put("userName", userBase.getUserName());
            if (!StringUtil.isEmpty(userBase.getFace())) {
                result.put("face", userBase.getFace());
            }
            if (!StringUtil.isEmpty(userBase.getMobile())) {
                result.put("mobile", userBase.getMobile());
            }
            if (!StringUtil.isEmpty(userBase.getEmail())) {
                result.put("email", userBase.getEmail());
            }
            if (!StringUtil.isEmpty(userBase.getCity())) {
                result.put("city", userBase.getCity());
            }
            result.put("gender", userBase.getGender());
            if (userBase.getBirthday() > 0) {
                result.put("birthday", DateUtils.formatDate(userBase.getBirthday()));
            }
            if (!StringUtil.isEmpty(userBase.getSignature())) {
                result.put("signature", userBase.getSignature());
            }
        }
        return result;
    }

}
