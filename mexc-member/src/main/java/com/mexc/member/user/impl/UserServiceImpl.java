package com.mexc.member.user.impl;


import com.alibaba.fastjson.JSON;
import com.laile.esf.common.util.DateUtil;
import com.laile.esf.common.util.MD5Util;
import com.laile.esf.common.util.RandomUtil;
import com.mexc.common.BusCode;
import com.mexc.common.util.LogUtil;
import com.mexc.common.util.PwdUtil;
import com.mexc.common.util.SensitiveInfoUtils;
import com.mexc.common.util.UUIDUtil;
import com.mexc.common.util.google.GoogleAuthenticator;
import com.mexc.common.util.jedis.RedisUtil;
import com.mexc.dao.delegate.member.MemberAssetDelegate;
import com.mexc.dao.delegate.member.MemberAuthDelegate;
import com.mexc.dao.delegate.member.MemberDelegate;
import com.mexc.dao.delegate.member.MexcLevelDelegate;
import com.mexc.dao.delegate.vcoin.VCoinDelegate;
import com.mexc.dao.dto.asset.AssetDto;
import com.mexc.dao.dto.asset.AssetQueryDto;
import com.mexc.dao.dto.member.LoginInfoDto;
import com.mexc.dao.dto.vcoin.BtcValueDto;
import com.mexc.dao.model.common.MexcLoginLog;
import com.mexc.dao.model.member.MexcLevel;
import com.mexc.dao.model.member.MexcMember;
import com.mexc.dao.model.member.MexcMemberAuth;
import com.mexc.dao.util.mail.MailContent;
import com.mexc.dao.util.mail.MailSender;
import com.mexc.member.dto.request.*;
import com.mexc.member.dto.response.*;
import com.mexc.member.user.IUserService;
import com.mexc.mq.core.IMqProducerService;
import com.mexc.mq.util.FastJsonMessageConverter;
import com.mexc.mq.vo.MqMsgVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.mexc.common.constant.RedisKeyConstant.REG_;

/**
 * Class Describe
 * <p>
 * User: yangguang
 * Date: 17/12/6
 * Time: ??????4:23
 */
@Service
public class UserServiceImpl implements IUserService {
    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    @Value("${HOST}")
    private String host;
    @Resource
    MemberDelegate memberDelegate;
    @Resource
    MexcLevelDelegate levelDelegate;
    @Resource
    MemberAuthDelegate memberAuthDelegate;

    @Resource
    IMqProducerService mqProducerService;

    @Value("${mq.queue.asset}")
    private String createAsset;

    @Resource
    FastJsonMessageConverter fastJsonMessageConverter;

    @Resource
    MemberAssetDelegate memberAssetDelegate;

    @Resource
    VCoinDelegate vCoinDelegate;


    private Executor executor = Executors.newFixedThreadPool(15);


    @Override
    public UserRegisterResponse register(UserRegisterRequest registerRequest) {
        UserRegisterResponse registerResponse = new UserRegisterResponse();

        /** ?????????????????????????????? **/
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(registerRequest.getRegAccount());
        if (mexcMember != null) {
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + mexcMember.getAccount() + "]?????????"));
            registerResponse.setCode(BusCode.MEXC_00001.getCode());
            registerResponse.setMsg(BusCode.MEXC_00001.getMsg());
            return registerResponse;
        }
        MexcMember register = new MexcMember();
        String uuid = UUIDUtil.get32UUID();
        register.setId(uuid);
        register.setStatus(0);
        register.setAccount(registerRequest.getRegAccount());
        register.setAccountPwd(PwdUtil.getMemberPwd(registerRequest.getRegAccount(), registerRequest.getHexPassword()));
        register.setAuthLevel(1);
        register.setCreateTime(new Date());
        int result = memberDelegate.insertSelective(register);
        register.setId(uuid);
        if (result < 1) {
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + registerRequest.getRegAccount() + "]????????????,????????????????????????"));
            registerResponse.setCode(BusCode.MEXC_99999.getCode());
            registerResponse.setMsg("???????????????,??????????????????");
            return registerResponse;
        }
        registerResponse.setCode(BusCode.MEXC_00000.getCode());
        registerResponse.setMsg(BusCode.MEXC_00000.getMsg());
        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + registerRequest.getRegAccount() + "]????????????"));
        /**
         * ??????????????????
         */
        sendRegisterMail(register);
        /**
         * ??????????????????
         */
        createAsset(register);
        return registerResponse;
    }

    @Override
    public LoginResponse login(LoginRequest loginRequest) {
        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + loginRequest.getAccount() + "]????????????"));
        MexcMember mexcMember = memberDelegate.queryMermberByAccountAndPwd(loginRequest.getAccount(), PwdUtil.getMemberPwd(loginRequest.getAccount(), loginRequest.getPassword()));
        LoginResponse response = new LoginResponse();
        if (mexcMember == null) {
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + loginRequest.getAccount() + "]????????????????????????"));
            response.setCode(BusCode.MEXC_00002.getCode());
            response.setMsg(BusCode.MEXC_00002.getMsg());
            return response;
        }
        Date now = new Date();
        try {
            LoginInfoDto loginInfoDto = new LoginInfoDto();
            loginInfoDto.setAccount(loginRequest.getAccount());
            loginInfoDto.setLoginIp(loginRequest.getIp());
            loginInfoDto.setLoginTime(now);
            memberDelegate.updateLoginInfo(loginInfoDto);
        } catch (Exception e) {
            logger.warn("??????????????????????????????", e);
        }
        response.setAccount(mexcMember.getAccount());
        response.setLastLoginIp(loginRequest.getIp());
        response.setCode(BusCode.MEXC_00000.getCode());
        response.setSecondAuthType(mexcMember.getSecondAuthType());
        response.setStatus(String.valueOf(mexcMember.getStatus()));
        response.setAuthLevel(String.valueOf(mexcMember.getAuthLevel()));

        //????????????btc???????????????
        AssetQueryDto assetQueryDto = new AssetQueryDto();
        assetQueryDto.setAccount(mexcMember.getAccount());
        Map<String,String> btcAndUsdValue = this.queryBtcAndUsdValue(assetQueryDto);
        response.setBtcValue(btcAndUsdValue.get("btcValue"));
        response.setUsdValue(btcAndUsdValue.get("usdValue"));

        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + loginRequest.getAccount() + "]????????????"));
        return response;
    }

    @Override
    public FinishRegisterResponse finishRegister(FinishRegisterRequest registerRequest) {
        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + registerRequest.getFrom() + "]????????????"));
        String uid = RedisUtil.get("reg_" + registerRequest.getFrom());
        String signStr = "rgvalid?t_k=" + uid + "&from=" + registerRequest.getFrom() + "&s_t=" + registerRequest.getS_t();
        String signEqual = MD5Util.MD5(signStr);
        FinishRegisterResponse registerResponse = new FinishRegisterResponse();
        if (!signEqual.equalsIgnoreCase(registerRequest.getSign())) {
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + registerRequest.getFrom() + "]????????????"));
            registerResponse.setCode(BusCode.MEXC_00003.getCode());
            registerResponse.setMsg(BusCode.MEXC_00003.getMsg());
            return registerResponse;
        }
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(registerRequest.getFrom());
        if (mexcMember.getStatus() != 0) {
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + registerRequest.getFrom() + "]????????????????????????"));
            registerResponse.setCode(BusCode.MEXC_00004.getCode());
            registerResponse.setMsg(BusCode.MEXC_00004.getMsg());
            return registerResponse;
        }
        memberDelegate.finishRegister(registerRequest.getFrom());
        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + registerRequest.getFrom() + "]????????????"));
        registerResponse.setAccount(SensitiveInfoUtils.email(mexcMember.getAccount()));
        registerResponse.setCode(BusCode.MEXC_00000.getCode());
        return registerResponse;
    }

    @Override
    public RegSendMailResponse reSendRegEmail(RegSendMailRequest mailRequest) {
        RegSendMailResponse regSendMailResponse = new RegSendMailResponse();
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(mailRequest.getAccount());
        if (mexcMember == null) {
            regSendMailResponse.setCode(BusCode.MEXC_00005.getCode());
            regSendMailResponse.setMsg(BusCode.MEXC_00005.getMsg());
            return regSendMailResponse;
        }
        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + mailRequest.getAccount() + "]?????????????????????"));
        String uid = UUID.randomUUID().toString();
        MailContent mailContent = new MailContent();
        mailContent.setUserTo(new String[]{mailRequest.getAccount()});
        mailContent.setTitle("??????????????????");
        RedisUtil.setex(REG_ + mailRequest.getAccount(), uid, 86400);
        String queryString = "rgvalid?t_k=" + uid + "&from=" + mailRequest.getAccount() + "&s_t=" + DateUtil.format(new Date(), DateUtil.YYYYMMDDHHMMSS);
        String sign = MD5Util.MD5(queryString);
        String url = host + queryString + "&sign=" + sign;
        logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + mailRequest.getAccount() + "]??????????????????:" + url));
        mailContent.setBody(MailSender.MAIL_MODEL.replace("${url}", url));
        MailSender.sendSingleMail(mailContent);
        regSendMailResponse.setCode(BusCode.MEXC_00000.getCode());
        regSendMailResponse.setAccount(mailRequest.getAccount());
        return regSendMailResponse;
    }

    public UserChangePwdResponse changePwd(UserChangePwdRequest changePwdRequest) {
        UserChangePwdResponse response = new UserChangePwdResponse();
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(changePwdRequest.getAccount());

        //???????????????
        String oldPwd = PwdUtil.getMemberPwd(changePwdRequest.getAccount(), changePwdRequest.getOldPwd());
        if (!oldPwd.equalsIgnoreCase(mexcMember.getAccountPwd())) {
            response.setCode(BusCode.MEXC_00009.getCode());
            response.setMsg(BusCode.MEXC_00009.getMsg());
            return response;
        }

        //????????????
        mexcMember.setAccountPwd(PwdUtil.getMemberPwd(changePwdRequest.getAccount(), changePwdRequest.getNewPwd()));
        int rows = memberDelegate.updateByPrimaryKeySelective(mexcMember);
        if (rows < 1) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg(BusCode.MEXC_99999.getMsg());
            return response;
        }
        response.setCode(BusCode.MEXC_00000.getCode());
        response.setMsg(BusCode.MEXC_00000.getMsg());
        return response;
    }

    /**
     * ??????google??????
     *
     * @param googleAuthRequest
     *
     * @return
     */
    public GoogleAuthBindResponse bindGoogleAuth(GoogleAuthRequest googleAuthRequest) {
        GoogleAuthBindResponse googleAuthResponse = new GoogleAuthBindResponse();

        //??????google?????????
        if (!this.checkGoogleCode(googleAuthRequest.getAccount(), googleAuthRequest.getValidationCode())) {
            googleAuthResponse.setCode(BusCode.MEXC_00008.getCode());
            googleAuthResponse.setMsg(BusCode.MEXC_00008.getMsg());
            return googleAuthResponse;
        }

        //??????????????????
        String pwd = PwdUtil.getMemberPwd(googleAuthRequest.getAccount(), googleAuthRequest.getPassword());
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(googleAuthRequest.getAccount());
        if (!mexcMember.getAccountPwd().equalsIgnoreCase(pwd)) {
            googleAuthResponse.setCode(BusCode.MEXC_00007.getCode());
            googleAuthResponse.setMsg(BusCode.MEXC_00007.getMsg());
            return googleAuthResponse;
        }
        //??????????????????????????????
        mexcMember.setSecondAuthType(2);
        memberDelegate.updateByPrimaryKeySelective(mexcMember);

        logger.info(LogUtil.msg("UserServiceImpl:bindGoogleAuth", "??????[" + googleAuthRequest.getAccount() + "]??????????????????"));
        googleAuthResponse.setSecret(mexcMember.getSecondPwd());
        googleAuthResponse.setCode(BusCode.MEXC_00000.getCode());
        googleAuthResponse.setMsg(BusCode.MEXC_00000.getMsg());
        return googleAuthResponse;
    }

    /**
     * ????????????
     *
     * @param googleAuthRequest
     *
     * @return
     */
    public BaseResponse unbindGoogleAuth(GoogleAuthRequest googleAuthRequest) {
        BaseResponse response = new BaseResponse();
        //??????google?????????
        if (!this.checkGoogleCode(googleAuthRequest.getAccount(), googleAuthRequest.getValidationCode())) {
            response.setCode(BusCode.MEXC_00008.getCode());
            response.setMsg(BusCode.MEXC_00008.getMsg());
            return response;
        }

        //??????????????????
        String pwd = PwdUtil.getMemberPwd(googleAuthRequest.getAccount(), googleAuthRequest.getPassword());
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(googleAuthRequest.getAccount());
        if (!mexcMember.getAccountPwd().equalsIgnoreCase(pwd)) {
            response.setCode(BusCode.MEXC_00007.getCode());
            response.setMsg(BusCode.MEXC_00007.getMsg());
            return response;
        }

        //??????????????????????????????
        mexcMember.setSecondAuthType(0);
        memberDelegate.updateByPrimaryKeySelective(mexcMember);

        logger.info(LogUtil.msg("UserServiceImpl:unbindGoogleAuth", "??????[" + googleAuthRequest.getAccount() + "]??????????????????"));
        response.setCode(BusCode.MEXC_00000.getCode());
        response.setMsg(BusCode.MEXC_00000.getMsg());
        return response;
    }

    /**
     * ??????google????????????????????????URL
     *
     * @param account ??????
     *
     * @return
     */
    public GoogleAuthInfoResponse getGoogleAuthInfo(String account) {
        GoogleAuthInfoResponse googleAuthInfoResponse = new GoogleAuthInfoResponse();
        String secret = GoogleAuthenticator.generateSecretKey();
        String qrcodeUrl = GoogleAuthenticator.getQRBarcodeURL(account, "mexc.com", secret);

        //???????????????
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(account);
        mexcMember.setSecondPwd(secret);
        memberDelegate.updateByPrimaryKeySelective(mexcMember);

        googleAuthInfoResponse.setSecret(secret);
        googleAuthInfoResponse.setQrcodeURL(qrcodeUrl);
        googleAuthInfoResponse.setCode(BusCode.MEXC_00000.getCode());
        googleAuthInfoResponse.setMsg(BusCode.MEXC_00000.getMsg());
        return googleAuthInfoResponse;
    }

    /**
     * ?????????
     *
     * @param account        ????????????
     * @param validationCode ?????????google?????????
     *
     * @return
     */
    public Boolean checkGoogleCode(String account, Long validationCode) {
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(account);
        String savedSecret = mexcMember.getSecondPwd();
        GoogleAuthenticator ga = new GoogleAuthenticator();
        ga.setWindowSize(5);
        return ga.checkCode(savedSecret, validationCode);
    }


    /**
     * ????????????????????????
     *
     * @param request
     *
     * @return
     */
    public BaseResponse identityAuth(IdentityAuthRequest request) {
        BaseResponse response = new BaseResponse();
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(request.getAccount());

        MexcMemberAuth mexcMemberAuth = new MexcMemberAuth();
        BeanUtils.copyProperties(request, mexcMemberAuth);
        mexcMemberAuth.setCreateBy(mexcMember.getId());
        mexcMemberAuth.setCreateByName(request.getAccount());
        mexcMemberAuth.setCreateTime(new Date());
        mexcMemberAuth.setMemberId(mexcMember.getId());
        mexcMemberAuth.setUpdateBy(mexcMember.getId());
        mexcMemberAuth.setUpdareByName(request.getAccount());
        mexcMemberAuth.setUpdateTime(new Date());
        mexcMemberAuth.setStatus(0);
        memberDelegate.identityAuth(mexcMemberAuth);
        response.setCode(BusCode.MEXC_00000.getCode());
        response.setMsg(BusCode.MEXC_00000.getMsg());
        return response;
    }


    public MexcMemberAuth queryIdentityInfo(String memberId) {
        return memberAuthDelegate.queryIdenAuthByMemberId(memberId);
    }

    @Override
    public MexcMember queryMemberByAccount(String account) {
        return memberDelegate.queryMermberByAccount(account);
    }

    @Override
    public List<MexcLoginLog> queryRecentLoginLog() {
        return memberDelegate.queryRecentLog();
    }

    public void createAsset(MexcMember mexcMember) {
        logger.info("???????????????????????????{}", JSON.toJSONString(mexcMember));
        executor.execute(() -> {
            MqMsgVo<Map<String, String>> mqMsgVo = new MqMsgVo<>();
            Map<String, String> map = new HashMap<>();
            map.put("account", mexcMember.getAccount());
            map.put("pwd", RandomUtil.randomStr(24));
            map.put("memberId", mexcMember.getId());
            map.put("iv", RandomUtil.randomStr(8));
            mqMsgVo.setMsgId(RandomUtil.randomStr(8))
                    .setContent(map)
                    .setInsertTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
            logger.info(LogUtil.msg("UserServiceImpl:creatAsset", "???????????????????????????????????????data:" + JSON.toJSONString(mqMsgVo)));
            try {
                mqProducerService.convertAndSend(createAsset, fastJsonMessageConverter.sendMessage(mqMsgVo));
            } catch (Exception e) {
                logger.error(LogUtil.msg("UserServiceImpl:creatAsset", "???????????????????????????????????????data:" + JSON.toJSONString(map)), e);
            }
        });
    }


    private void sendRegisterMail(MexcMember register) {
        new Thread(() -> {
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + register.getAccount() + "]?????????????????????"));
            String uid = UUID.randomUUID().toString();
            RedisUtil.setex(REG_ + register.getAccount(), uid, 86400);
            MailContent mailContent = new MailContent();
            mailContent.setUserTo(new String[]{register.getAccount()});
            mailContent.setTitle("??????????????????");
            String queryString = "rgvalid?t_k=" + uid + "&from=" + register.getAccount() + "&s_t=" + DateUtil.format(new Date(), DateUtil.YYYYMMDDHHMMSS);
            String sign = MD5Util.MD5(queryString);
            String url = host + queryString + "&sign=" + sign;
            logger.info(LogUtil.msg("UserServiceImpl:register", "??????[" + register.getAccount() + "]??????????????????:" + url));
            mailContent.setBody(MailSender.MAIL_MODEL.replace("${url}", url));
            MailSender.sendSingleMail(mailContent);
        }).start();
    }


    public Map<String, String> cashLimit() {
        Map<String, String> levelMap = new HashMap<>();
        List<MexcLevel> list = levelDelegate.selectAll();
        if (CollectionUtils.isEmpty(list)) {
            levelMap.put("1", "2");
            levelMap.put("2", "100");
        } else {
            for (MexcLevel level : list) {
                if (level.getLevel() == 1) {
                    levelMap.put("1", level.getLimitAmount().stripTrailingZeros().toPlainString());
                } else if (level.getLevel() == 2) {
                    levelMap.put("2", level.getLimitAmount().stripTrailingZeros().toPlainString());
                }
            }
        }
        return levelMap;
    }

    /**
     * ?????????????????????
     * @param email
     * @return
     */
    public BaseResponse sendEmailAuthCode(String email) {
        BaseResponse response = new BaseResponse();
        MexcMember member = queryMemberByAccount(email);
        if(member == null) {
            response.setCode(BusCode.MEXC_00005.getCode());
            response.setMsg(BusCode.MEXC_00005.getMsg());
            return  response;
        }
        try {
            MailContent mailContent = new MailContent();
            mailContent.setUserTo(new String[]{email});
            mailContent.setTitle("???????????????");
            String code = com.mexc.common.util.RandomUtil.getRandom();
            RedisUtil.setex(REG_ + email, code, 86400);
            mailContent.setBody(MailSender.MAIL_AUTH_CODE.replace("${code}", code));
            MailSender.sendSingleMail(mailContent);
        }catch (Exception e) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg(BusCode.MEXC_99999.getMsg());
            logger.error("???????????????????????????");
            return response;
        }
        response.setCode(BusCode.MEXC_00000.getCode());
        response.setMsg(BusCode.MEXC_00000.getMsg());

        return response;
    }

    @Override
    public BaseResponse checkEmail(CheckEmailRequest checkEmailRequest) {
        EmailAuthResponse response = new EmailAuthResponse();
        MexcMember member = queryMemberByAccount(checkEmailRequest.getAccount());
        if(member == null) {
            response.setCode(BusCode.MEXC_00005.getCode());
            response.setMsg(BusCode.MEXC_00005.getMsg());
            return response;
        }
        if(member.getStatus() == 0) {
            response.setCode(BusCode.MEXC_00013.getCode());
            response.setMsg(BusCode.MEXC_00013.getMsg());
            return response;
        }
        response.setCode(BusCode.MEXC_00000.getCode());
        response.setMsg(BusCode.MEXC_00000.getMsg());
        return response;
    }

    /**
     * ????????????
     * @param emailAuthRequest
     * @return
     */
    public EmailAuthResponse resetPassword(EmailAuthRequest emailAuthRequest) {
        EmailAuthResponse response = new EmailAuthResponse();
        MexcMember member = queryMemberByAccount(emailAuthRequest.getAccount());
        if(member == null) {
            response.setCode(BusCode.MEXC_00005.getCode());
            response.setMsg(BusCode.MEXC_00005.getMsg());
            return response;
        }
        try {
            String code = RedisUtil.get(REG_ + emailAuthRequest.getAccount());
            if(StringUtils.isEmpty(code) || !emailAuthRequest.getAuthCode().equals(code)) {
                response.setCode(BusCode.MEXC_00008.getCode());
                response.setMsg(BusCode.MEXC_00008.getMsg());
                return response;
            }
            //????????????
            member.setAccountPwd(PwdUtil.getMemberPwd(emailAuthRequest.getAccount(), emailAuthRequest.getNewPassword()));
            int rows = memberDelegate.updateByPrimaryKeySelective(member);
            if (rows < 1) {
                response.setCode(BusCode.MEXC_99999.getCode());
                response.setMsg(BusCode.MEXC_99999.getMsg());
                return response;
            }
            response.setCode(BusCode.MEXC_00000.getCode());
            response.setMsg(BusCode.MEXC_00000.getMsg());

        }catch (Exception e) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg(BusCode.MEXC_99999.getMsg());
            logger.error("??????????????????");
        }
        return response;
    }

    /**
     * ????????????btc?????????????????????
     * @param assetQueryDto
     * @return
     */
    public Map<String,String> queryBtcAndUsdValue(AssetQueryDto assetQueryDto) {
        Map<String,String> result = new HashMap<>();
        List<AssetDto> assetList = memberAssetDelegate.queryMermberAsset(assetQueryDto);
        BigDecimal sumBtcValue = new BigDecimal("0");//btc????????????
        BigDecimal sumUsdValue;//??????????????????
        BigDecimal currentUsdPrice = new BigDecimal("0");
        if(!CollectionUtils.isEmpty(assetList)) {
            for(AssetDto assetDto : assetList) {
                String key = assetDto.getVcoinNameEn()+"-"+assetDto.getVcoinId();
                String btcValueJson = RedisUtil.get(key);
                BtcValueDto btcValue = JSON.parseObject(btcValueJson, BtcValueDto.class);
                if(assetDto.getTotalAmount()==null){
                    assetDto.setTotalAmount(BigDecimal.ZERO);
                }
                if(btcValue != null) {
                    BigDecimal currentVCoinBtcValue = assetDto.getTotalAmount().multiply(new BigDecimal(btcValue.getPriceBtc()));
                    sumBtcValue = sumBtcValue.add(currentVCoinBtcValue);
                    currentUsdPrice = new BigDecimal(btcValue.getPriceUsb());
                }
            }
        }
        sumUsdValue = sumBtcValue.multiply(currentUsdPrice);//??????=btc*????????????
        result.put("usdValue",sumUsdValue.toPlainString());
        result.put("btcValue",sumBtcValue.toPlainString());
        return result;
    }
}
