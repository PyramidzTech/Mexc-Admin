package com.mexc.web.core.service.asset.impl;

import com.alibaba.fastjson.JSON;
import com.laile.esf.common.exception.BusinessException;
import com.laile.esf.common.exception.ResultCode;
import com.laile.esf.common.exception.SystemException;
import com.laile.esf.common.util.DateUtil;
import com.laile.esf.common.util.MD5Util;
import com.laile.esf.common.util.RandomUtil;
import com.mexc.common.BusCode;
import com.mexc.common.constant.CommonConstant;
import com.mexc.common.util.BigDecimalFormat;
import com.mexc.common.util.LogUtil;
import com.mexc.common.util.ThressDescUtil;
import com.mexc.common.util.UUIDUtil;
import com.mexc.common.util.google.GoogleAuthenticator;
import com.mexc.common.util.jedis.RedisUtil;
import com.mexc.dao.delegate.member.AddressMarkDelegate;
import com.mexc.dao.delegate.member.MemberAssetDelegate;
import com.mexc.dao.delegate.member.MemberDelegate;
import com.mexc.dao.delegate.member.MexcLevelDelegate;
import com.mexc.dao.delegate.vcoin.VCoinDelegate;
import com.mexc.dao.delegate.vcoin.VCoinFeeDelegate;
import com.mexc.dao.delegate.wallet.MexcAssetCashDelegate;
import com.mexc.dao.delegate.wallet.MexcColdTransDelegate;
import com.mexc.dao.delegate.wallet.MexcEthHotTransDelegate;
import com.mexc.dao.dto.asset.AssetDto;
import com.mexc.dao.dto.asset.AssetQueryDto;
import com.mexc.dao.dto.vcoin.BtcValueDto;
import com.mexc.dao.model.member.MexcAddressMark;
import com.mexc.dao.model.member.MexcLevel;
import com.mexc.dao.model.member.MexcMember;
import com.mexc.dao.model.member.MexcMemberAsset;
import com.mexc.dao.model.vcoin.MexcAddressLib;
import com.mexc.dao.model.vcoin.MexcVCoin;
import com.mexc.dao.model.vcoin.MexcVCoinFee;
import com.mexc.dao.model.wallet.MexcAssetCash;
import com.mexc.dao.model.wallet.MexcColdTrans;
import com.mexc.dao.model.wallet.MexcEthHotTrans;
import com.mexc.dao.util.mail.MailContent;
import com.mexc.dao.util.mail.MailSender;
import com.mexc.dao.vo.asset.AssetCashHistoryVo;
import com.mexc.mq.core.IMqProducerService;
import com.mexc.mq.util.FastJsonMessageConverter;
import com.mexc.mq.vo.MqMsgVo;
import com.mexc.vcoin.TokenEnum;
import com.mexc.vcoin.WalletHolder;
import com.mexc.vcoin.api.model.WalletAccount;
import com.mexc.vcoin.bitcoin.BitServerUtil;
import com.mexc.vcoin.eth.ERC20TokenUtil;
import com.mexc.vcoin.eth.ParityClientWrapper;
import com.mexc.web.core.model.request.MemberAssetCashRequest;
import com.mexc.web.core.model.request.UserWalletToClodRequest;
import com.mexc.web.core.model.response.MemberAssetCashResponse;
import com.mexc.web.core.service.asset.IAssetService;
import com.neemre.btcdcli4j.core.domain.Transaction;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import javax.annotation.Resource;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static com.mexc.common.constant.RedisKeyConstant.ADDRESS_LIB_NOT_USED;
import static com.mexc.common.constant.RedisKeyConstant.CASH_;

/**
 * Class Describe
 * <p>
 * User: yangguang
 * Date: 17/12/14
 * Time: ??????5:56
 */
@Service
public class AssetServiceImpl implements IAssetService {

    private static Logger logger = LoggerFactory.getLogger(AssetServiceImpl.class);
    public final static BigDecimal btcTxFee = new BigDecimal("0.00055");
    @Value("${eth_path}")
    private String eth_path;
    @Value("${HOST}")
    private String host;
    @Value("${mq.queue.cash}")
    private String cashQue;
    @Resource
    VCoinFeeDelegate vCoinFeeDelegate;
    @Resource
    MemberAssetDelegate memberAssetDelegate;
    @Resource
    MexcEthHotTransDelegate hotTransDelegate;
    @Resource
    MexcColdTransDelegate coldTransDelegate;
    @Resource
    VCoinDelegate vCoinDelegate;
    @Resource
    MexcAssetCashDelegate assetCashDelegate;
    @Resource
    VCoinFeeDelegate coinFeeDelegate;
    @Resource
    MemberDelegate memberDelegate;
    @Resource
    AddressMarkDelegate addressMarkDelegate;
    @Resource
    MexcLevelDelegate levelDelegate;
    @Resource
    IMqProducerService mqProducerService;

    @Resource
    FastJsonMessageConverter fastJsonMessageConverter;

    public List<AssetDto> queryMemberAsset(AssetQueryDto queryDto) {
        List<AssetDto> assetDtos = memberAssetDelegate.queryMermberAsset(queryDto);
        List<AssetDto> assetAddressList = new ArrayList<>();
        Set<String> tokenSet = new HashSet<>();
        for (AssetDto assetDto : assetDtos) {
            if (StringUtils.isNotEmpty(assetDto.getAssetId())) {
                //???redis???btc??????
                String key = assetDto.getVcoinNameEn() + "-" + assetDto.getVcoinId();
                String valueJson = RedisUtil.get(key);
                BtcValueDto btcValueDto = JSON.parseObject(valueJson, BtcValueDto.class);
                if (btcValueDto != null) {
                    if (assetDto.getTotalAmount() != null) {
                        BigDecimal btcAmount = assetDto.getTotalAmount().multiply(new BigDecimal(btcValueDto.getPriceBtc()));//??????*??????
                        assetDto.setBtcAmount(btcAmount);
                    }
                }
                //?????????
                assetDto.setWalletAddress(ThressDescUtil.decodeAssetAddress(assetDto.getWalletAddress()));
            } else {
                assetAddressList.add(assetDto);
            }
            MexcVCoin mexcVCoin = vCoinDelegate.selectByPrimaryKey(assetDto.getVcoinId());
            logger.info("??????token:{}", assetDto.getVcoinId());
            tokenSet.add(mexcVCoin.getVcoinToken());
        }
        if (CollectionUtils.isNotEmpty(assetAddressList)) {
            MexcMember mexcMember = memberDelegate.queryMermberByAccount(queryDto.getAccount());
            List<MexcMemberAsset> mexcMemberAssets = new ArrayList<>();
            logger.info("????????????????????????");
            Iterator iterator = tokenSet.iterator();
            Map<String, MexcAddressLib> tokenAddress = new HashMap<>();
            while (iterator.hasNext()) {
                String token = (String) iterator.next();
                //????????????????????????token???????????????
                MexcMemberAsset exists = memberAssetDelegate.selectMemberAssetByToken(token, mexcMember.getId());
                if (exists == null) {
                    String addressJson = RedisUtil.spop(token.toUpperCase() + "_" + ADDRESS_LIB_NOT_USED);
                    logger.info("???redis??????????????????token address:{}", addressJson);
                    tokenAddress.put(token.toUpperCase(), JSON.parseObject(addressJson, MexcAddressLib.class));
                } else {
                    MexcAddressLib lib = new MexcAddressLib();
                    lib.setIv(exists.getIv());
                    lib.setPwd(exists.getAccountPwd());
                    lib.setFilepath(exists.getFilePath());
                    lib.setAddress(exists.getWalletAddress());
                    lib.setPhrase(exists.getAccountPhrase());
                    lib.setType(token);
                    tokenAddress.put(token.toUpperCase(), lib);
                }
            }
            logger.info("token????????????????????????:{}", JSON.toJSONString(tokenAddress));
            try {
                for (AssetDto assetDto : assetAddressList) {
                    MexcVCoin mexcVCoin = vCoinDelegate.selectByPrimaryKey(assetDto.getVcoinId());
                    //???????????????
                    MexcMemberAsset asset = new MexcMemberAsset();
                    asset.setId(UUIDUtil.get32UUID());
                    asset.setBalanceAmount(BigDecimal.ZERO);
                    asset.setAccount(mexcMember.getAccount());
                    asset.setBtcAmount(BigDecimal.ZERO);
                    asset.setFrozenAmount(BigDecimal.ZERO);
                    asset.setMemberId(mexcMember.getId());
                    asset.setStatus(1);
                    Date now = new Date();
                    asset.setCreateTime(now);
                    asset.setCreateBy("system");
                    asset.setUpdateTime(now);
                    asset.setUpdateBy("system");
                    asset.setVcoinId(assetDto.getVcoinId());
                    asset.setTotalAmount(BigDecimal.ZERO);
                    asset.setToken(mexcVCoin.getVcoinToken());
                    MexcAddressLib lib = tokenAddress.get(mexcVCoin.getVcoinToken());
                    asset.setFilePath(lib.getFilepath());
                    asset.setAccountPwd(lib.getPwd());
                    asset.setIv(lib.getIv());
                    asset.setWalletAddress(lib.getAddress());
                    asset.setAccountPhrase(lib.getPhrase());
                    mexcMemberAssets.add(asset);
                }
                //??????????????????????????????
                addAsset(mexcMemberAssets);
                //????????????????????????
                assetDtos = memberAssetDelegate.queryMermberAsset(queryDto);
                for (AssetDto assetDto : assetDtos) {
                    if (StringUtils.isNotEmpty(assetDto.getAssetId())) {
                        //???redis???btc??????
                        String key = assetDto.getVcoinNameEn() + "-" + assetDto.getVcoinId();
                        String valueJson = RedisUtil.get(key);
                        BtcValueDto btcValueDto = JSON.parseObject(valueJson, BtcValueDto.class);
                        if (btcValueDto != null) {
                            if (assetDto.getTotalAmount() != null) {
                                BigDecimal btcAmount = assetDto.getTotalAmount().multiply(new BigDecimal(btcValueDto.getPriceBtc()));//??????*??????
                                assetDto.setBtcAmount(btcAmount);
                            }
                        }
                        //?????????
                        assetDto.setWalletAddress(ThressDescUtil.decodeAssetAddress(assetDto.getWalletAddress()));
                    }
                }
            } catch (Exception e) {
                logger.info("??????????????????", e);
                MailSender.sendWarningMail("?????????????????????????????? memberId:" + mexcMember.getId());
            }
        }
        return assetDtos;
    }


    @Override
    public int addAsset(MexcMemberAsset asset) {
        return memberAssetDelegate.insertSelective(asset);
    }

    @Override
    public int addAsset(List<MexcMemberAsset> asset) {
        return memberAssetDelegate.insertMemberAsset(asset, asset.get(0).getMemberId());
    }


    @Override
    public boolean userWalletToClodWallet(UserWalletToClodRequest request) {
        if (request.getMainToken().equalsIgnoreCase(TokenEnum.ETH_COIN.getCode())) {
            ethAssetToCold(request);
        } else if (request.getMainToken().equalsIgnoreCase(TokenEnum.BIT_COIN.getCode())) {
            bitAssetToCold(request);
        }
        return false;
    }


    public boolean cashSuccess(MexcAssetCash cash) {
        try {
            assetCashDelegate.cashSuccess(cash);
        } catch (Exception e) {
            logger.warn("????????????????????????????????????", e);
            return false;
        }
        return true;
    }

    public boolean cashAutoFailed(MexcAssetCash cash) {
        try {
            assetCashDelegate.cashAutoFailed(cash);
        } catch (Exception e) {
            return false;
        }
        return true;
    }


    public boolean cashFailture(MexcAssetCash cash) {
        return assetCashDelegate.cashFailture(cash);
    }

    @Override
    public boolean googleCodeCheck(String account, String code) {
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(account);
        if (StringUtils.isEmpty(mexcMember.getSecondPwd())) {
            return false;
        }
        if (mexcMember.getSecondAuthType() == null || mexcMember.getSecondAuthType() != 2) {
            return false;
        }
        GoogleAuthenticator ga = new GoogleAuthenticator();
        ga.setWindowSize(5);
        return ga.checkCode(mexcMember.getSecondPwd(), Long.valueOf(code));
    }

    @Override
    public List<MexcAddressMark> queryMarkList(String account, String assetId) {
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(account);
        List<MexcAddressMark> list = addressMarkDelegate.assetMarkList(assetId, mexcMember.getId());
        if (CollectionUtils.isNotEmpty(list)) {
            for (MexcAddressMark addressMark : list) {
                addressMark.setAddress(ThressDescUtil.decodeAssetAddress(addressMark.getAddress()));
            }
        }
        return list;
    }

    public Map<String, String> levelCashLimit(MexcMember mexcMember, String vcoinId, BigDecimal cashValue) {
        //????????????????????????
        MexcLevel level = levelDelegate.queryByLevel(mexcMember.getAuthLevel());
        List<AssetCashHistoryVo> cashHis = assetCashDelegate.checkLevelLimit(mexcMember.getId());

        //????????????????????????
        AssetCashHistoryVo currentCash = new AssetCashHistoryVo();
        currentCash.setVcoinId(vcoinId);
        currentCash.setCashAmountSum(cashValue);
        cashHis.add(currentCash);

        //??????????????????????????????????????????????????????btc???????????????
        BigDecimal estimateBtcSum = new BigDecimal("0");
        for (AssetCashHistoryVo cashHistory : cashHis) {
            MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(cashHistory.getVcoinId());
            String key = vCoin.getVcoinNameEn().toUpperCase() + "-" + vCoin.getId();
            String btcValueJson = RedisUtil.get(key);

            //???????????????  ????????????
            if (!StringUtils.isEmpty(btcValueJson)) {
                BtcValueDto valueDto = JSON.parseObject(btcValueJson, BtcValueDto.class);
                BigDecimal estimateBtc = new BigDecimal(valueDto.getPriceBtc());
                estimateBtcSum = estimateBtcSum.add(estimateBtc);
            }
        }
        //????????????
        if (estimateBtcSum.compareTo(level.getLimitAmount()) > 0) {
            Map<String, String> returnMap = new HashMap<>();
            returnMap.put("result", "false");
            return returnMap;
        } else {
            return null;
        }
    }

    @Override
    public MemberAssetCashResponse assetCashCheck(String assetId, String amount) {
        MemberAssetCashResponse response = new MemberAssetCashResponse();
        if (StringUtils.isEmpty(assetId)) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("????????????????????????");
            return response;
        }
        if (StringUtils.isEmpty(amount)) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("??????????????????");
            return response;
        }
        MexcMemberAsset asset = memberAssetDelegate.selectByPrimaryKey(assetId);
        MexcVCoinFee vCoinFee = vCoinFeeDelegate.queryVcoinFeeByVcoinId(asset.getVcoinId());
        MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(asset.getVcoinId());
        MexcMember mexcMember = memberDelegate.selectByPrimaryKey(asset.getMemberId());
        BigDecimal cashAmount = new BigDecimal(amount);
        if (cashAmount.compareTo(vCoinFee.getCashLimitMin()) < 0) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("?????????????????????????????? " + BigDecimalFormat.roundScale(vCoinFee.getCashLimitMin(), 8).stripTrailingZeros().toPlainString());
            return response;
        }

        if (cashAmount.compareTo(vCoinFee.getCashLimitMax()) > 1) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("?????????????????????????????? " + BigDecimalFormat.roundScale(vCoinFee.getCashLimitMin(), 8).stripTrailingZeros().toPlainString());
            return response;
        }

        if (cashAmount.compareTo(vCoinFee.getCashLimitMax()) > 1) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("?????????????????????????????? " + BigDecimalFormat.roundScale(vCoinFee.getCashLimitMin(), 8).stripTrailingZeros().toPlainString());
            return response;
        }
        BigDecimal assetBalance = asset.getBalanceAmount();
        //????????????????????????????????????
        Map<String, String> checkResult = levelCashLimit(mexcMember, vCoin.getId(), cashAmount);
        if (checkResult != null) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("??????????????????,24???????????????????????????");
            return response;
        }
        if (assetBalance.compareTo(cashAmount) < 0) {
            response.setCode(BusCode.MEXC_99999.getCode());
            response.setMsg("??????????????????");
            return response;
        }
        if (mexcMember.getSecondAuthType() != null && mexcMember.getSecondAuthType() == 2) {
            response.setCashCheck(true);
        }
        response.setActualAmount(cashAmount.subtract(vCoinFee.getCashRate()).stripTrailingZeros().toPlainString());
        response.setCode(BusCode.MEXC_00000.getCode());
        return response;
    }

    @Override
    public BigDecimal assetEstimateBtc() {
        return null;
    }


    public MemberAssetCashResponse memberAssetCash(MemberAssetCashRequest request) {
        MemberAssetCashResponse cashResponse = new MemberAssetCashResponse();
        if (StringUtils.isEmpty(request.getCashAddress())) {
            cashResponse.setCode(BusCode.MEXC_99999.getCode());
            cashResponse.setMsg("????????????????????????");
            return cashResponse;
        }
        cashResponse = assetCashCheck(request.getAssetId(), request.getCashValue());
        if (!cashResponse.getCode().equals(BusCode.MEXC_00000.getCode())) {
            return cashResponse;
        }

        MexcMember mexcMember = memberDelegate.queryMermberByAccount(request.getAccount());
        MexcMemberAsset asset = memberAssetDelegate.selectByPrimaryKey(request.getAssetId());
        MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(asset.getVcoinId());
        MexcVCoinFee vCoinFee = coinFeeDelegate.queryVcoinFeeByVcoinId(vCoin.getId());


        Date now = new Date();
        MexcAssetCash mexcAssetCash = new MexcAssetCash();
        String cashId = UUIDUtil.get32UUID();
        mexcAssetCash.setId(cashId);
        mexcAssetCash.setMemberId(mexcMember.getId());
        mexcAssetCash.setCashStatus(0);
        mexcAssetCash.setTxAmount(request.getCashValue());
        mexcAssetCash.setTxApplyTime(now);
        mexcAssetCash.setAssetAddress(asset.getWalletAddress());
        mexcAssetCash.setTxCashAddress(ThressDescUtil.encodeAssetAddress(request.getCashAddress()));
        mexcAssetCash.setTxApplyToken(vCoin.getVcoinToken());
        mexcAssetCash.setTxApplyStatus(1);
        mexcAssetCash.setTxApplyValue(new BigDecimal(request.getCashValue()));
        mexcAssetCash.setAssetId(asset.getId());
        mexcAssetCash.setTxApplyToken(vCoin.getVcoinName());
        mexcAssetCash.setCashFee(vCoinFee.getCashRate());
        mexcAssetCash.setVcoinId(asset.getVcoinId());
        BigDecimal cashValue = new BigDecimal(request.getCashValue());
        mexcAssetCash.setActualAmount(cashValue.subtract(vCoinFee.getCashRate()));
        String token = UUIDUtil.get32UUID();
        mexcAssetCash.setTxTransCode(token);
        mexcAssetCash.setCashStatus(0);
//        List<MexcAssetTrans> transList = new ArrayList<>();
//        MexcAssetTrans cashTrans = new MexcAssetTrans();
//        cashTrans.setId(UUIDUtil.get32UUID());
//        cashTrans.setAssetId(request.getAssetId());
//        //-1:?????? 1:?????? 2:?????? 3:?????????
//        cashTrans.setTradeType("-1");
//        cashTrans.setTransAmount(cashValue);
//        cashTrans.setTransTime(now);
//        cashTrans.setTransNo(mexcAssetCash.getId());
//        transList.add(cashTrans);
//        MexcAssetTrans feeTrans = new MexcAssetTrans();
//        feeTrans.setId(UUIDUtil.get32UUID());
//        feeTrans.setAssetId(request.getAssetId());
//        //-1:?????? 1:?????? 2:?????? 3:????????? 7:???????????????
//        feeTrans.setTradeType("7");
//        feeTrans.setTransAmount(vCoinFee.getCashRate());
//        feeTrans.setTransTime(now);
//        feeTrans.setTransNo(mexcAssetCash.getId());
//        transList.add(feeTrans);
        boolean result = assetCashDelegate.localUserAsset(mexcAssetCash, null);
        try {
            addressMarkDelegate.saveHisAddress(mexcMember.getId(), request.getAddressTab(), asset.getId(), ThressDescUtil.encodeAssetAddress(request.getCashAddress()));
        } catch (Exception e) {
            logger.error("????????????????????????????????????", e);
        }
        if (result) {
            cashResponse.setCode(BusCode.MEXC_00000.getCode());
            sendCashConfirmMail(token, mexcMember.getAccount(), cashId);
            return cashResponse;
        } else {
            cashResponse.setCode(BusCode.MEXC_00000.getCode());
            cashResponse.setMsg("????????????,???????????????????????????????????????");
            return cashResponse;
        }
    }

    @Override
    public void cashConfirm(String s_t, String t_k, String sign, String cash_id) {
        String salt = RedisUtil.get("cash_" + t_k);
        if (StringUtils.isEmpty(salt)) {
            logger.info("????????????");
            throw new BusinessException(ResultCode.COMMON_ERROR, "???????????????,???????????????");
        }
        MexcAssetCash cash = assetCashDelegate.selectByPrimaryKey(cash_id);
        if (cash.getCashStatus() != 0) {
            throw new BusinessException(ResultCode.COMMON_ERROR, "???????????????,??????????????????");
        }
        String queryString = "cash_confirm?c_id=" + cash_id + "&t_k=" + t_k + "&s_t=" + s_t;
        String validSign = MD5Util.MD5(queryString + salt);
        if (!validSign.equalsIgnoreCase(sign)) {
            throw new BusinessException(ResultCode.COMMON_ERROR, "????????????,????????????????????????");
        }
        RedisUtil.del("cash_" + t_k);
        boolean result = assetCashDelegate.cashConfirm(cash_id);
        if (!result) {
            throw new BusinessException(ResultCode.COMMON_ERROR, "??????????????????,???????????????");
        }

        MqMsgVo<Map<String, String>> mqMsgVo = new MqMsgVo<>();
        Map<String, String> map = new HashMap<>();
        map.put("cashId", cash_id);
        mqMsgVo.setMsgId(RandomUtil.randomStr(8))
                .setContent(map)
                .setInsertTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
        logger.info(LogUtil.msg("UserServiceImpl:creatAsset", "???????????????????????????????????????data:" + JSON.toJSONString(mqMsgVo)));
        mqProducerService.convertAndSend(cashQue, fastJsonMessageConverter.sendMessage(mqMsgVo));
        logger.info(LogUtil.msg("UserServiceImpl:creatAsset", "???????????????????????????????????????data:" + JSON.toJSONString(mqMsgVo)));
    }

    private void sendCashConfirmMail(String token, String account, String cashId) {
        new Thread(() -> {
            logger.info(LogUtil.msg("AssetServiceImpl:memberAssetCash", "??????[" + account + "]????????????????????? id:[" + cashId + "]"));
            MailContent mailContent = new MailContent();
            mailContent.setUserTo(new String[]{account});
            mailContent.setTitle("????????????");
            String queryString = "cash_confirm?c_id=" + cashId + "&t_k=" + token + "&s_t=" + DateUtil.format(new Date(), DateUtil.YYYYMMDDHHMMSS);
            String salt = RandomUtil.randomStr(5);
            RedisUtil.set(CASH_ + token, salt);
            String sign = MD5Util.MD5(queryString + salt);
            String url = host + queryString + "&sign=" + sign;
            logger.info(LogUtil.msg("AssetServiceImpl:memberAssetCash", "??????[" + account + "] token:" + token));
            mailContent.setBody(MailSender.CASH_MODEL.replace("${url}", url));
            MailSender.sendSingleMail(mailContent);
        }).start();
    }


    public String ethCash(MexcVCoin vCoin, MexcMemberAsset asset, String cashAddress, BigDecimal amount) {
        String hotWalletAddress = WalletHolder.getParityClient().getEthWallInfo().getHotAddress();
        String hotWalletPwd = WalletHolder.getParityClient().getEthWallInfo().getHotAddressPwd();
        BigDecimal sendValue = ERC20TokenUtil.parseunit(vCoin.getScale(), amount.toPlainString());
        if (vCoin.getMainCoin() == 1) {
            BigInteger hotBalance = ERC20TokenUtil.queryBalance(WalletHolder.getParityClient().getEthWallInfo().getHotAddress());
            if (hotBalance.compareTo(amount.toBigInteger()) < 1) {
                MailSender.sendWarningMail("eth?????????????????????");
                throw new SystemException(ResultCode.COMMON_ERROR, "eth?????????????????????");
            }
            String hotToUserTxHash = ERC20TokenUtil.postTransaction(hotWalletAddress, cashAddress, BigInteger.valueOf(21000), sendValue.toBigInteger(), hotWalletPwd);
            if (StringUtils.isEmpty(hotToUserTxHash)) {
                logger.error("??????????????????????????????");
                return null;
            } else {
                return hotToUserTxHash;
            }

        } else {
            String strTokenBalance = ERC20TokenUtil.queryTokenBalance(WalletHolder.getParityClient().getEthWallInfo().getHotAddress(), vCoin.getContractAddress());

            BigInteger hotTokenBalance = ERC20TokenUtil.parseAmount(vCoin.getScale(),strTokenBalance).toBigInteger();
            if (hotTokenBalance.compareTo(amount.toBigInteger()) < 1) {
                MailSender.sendWarningMail("eth??????[" + vCoin.getVcoinName() + "]???????????????????????????");
                throw new SystemException(ResultCode.COMMON_ERROR, "eth??????[" + vCoin.getVcoinName() + "]???????????????????????????");
            }
            String transHash;
            try {
                transHash = ERC20TokenUtil.sendTransferTokensTransaction(hotWalletAddress, cashAddress, vCoin.getContractAddress(), hotWalletPwd, sendValue.toBigInteger(), WalletHolder.getParityClient());
                return transHash;
            } catch (Exception e) {
                logger.error("??????????????????---->to_address:" + cashAddress, e);
                MailSender.sendWarningMail("??????????????????---->to_address:" + cashAddress);
                throw new SystemException(ResultCode.COMMON_ERROR, "??????????????????---->to_address:" + cashAddress);
            }
        }
    }

    @Override
    public Transaction btcCash(MexcVCoin vCoin, MexcMemberAsset asset, String cashAddress, BigDecimal cash) {
        BigDecimal btcBalance = BitServerUtil.getBalance(WalletHolder.getBtcdClient().getBtcWallInfo().getHotAddress());
        if (btcBalance.compareTo(cash) < 1) {
            MailSender.sendWarningMail("btc?????????????????????");
            throw new SystemException(ResultCode.COMMON_ERROR, "btc?????????????????????");
        }
        String transHash = BitServerUtil.sendTo(cashAddress, cash);
        return BitServerUtil.getReceipt(transHash);
    }


    private boolean bitAssetToCold(UserWalletToClodRequest request) {
        logger.info("???????????????????????????");
        MexcColdTrans coldTrans = btcTransferToCold(request);
        try {
            BigDecimal actualTransValue = request.getValue().subtract(btcTxFee);
            //??????????????????????????????
            String userToColdTxHash = BitServerUtil.sendTo(WalletHolder.getBtcdClient().getBtcWallInfo().getColdAddress(), actualTransValue);
            if (StringUtils.isEmpty(userToColdTxHash)) {
                throw new SystemException(ResultCode.COMMON_ERROR, "?????????????????????????????????");
            }
            Transaction receipt = BitServerUtil.getReceipt(userToColdTxHash);
            coldTrans.setTxHash(userToColdTxHash);
            coldTrans.setGasPrice(BigDecimal.ONE);
            coldTrans.setGasUsed(receipt.getFee());
            coldTrans.setTxReceipt(JSON.toJSONString(receipt));
            coldTrans.setTxAmount(receipt.getAmount());
            coldTrans.setTxFee(receipt.getFee());
            coldTransDelegate.updateByPrimaryKeySelective(coldTrans);
            return true;
        } catch (Exception e) {
            logger.error("??????????????????????????????????????????", e);
            MailSender.sendWarningMail("??????????????????????????????????????????" + e.getMessage());
            throw new SystemException(ResultCode.COMMON_ERROR, "?????????????????????????????????");
        }
    }


    private boolean ethAssetToCold(UserWalletToClodRequest request) {
        //???????????????????????????
        //???????????????,????????????????????????????????????????????????,??????????????????????????????gasprice*gas???????????????
        logger.info("eth???????????????????????????");
        BigInteger gasprice = ERC20TokenUtil.getGasPrice();
        logger.info("eth?????????????????????????????????gas price:{}", gasprice);
        String walletAddress = ThressDescUtil.decodeAssetAddress(request.getUserWalletAddressSecret());
        logger.info("eth?????????????????????????????????????????????:{}", walletAddress);
        String assetPwd = ThressDescUtil.decodeAssetPwd(request.getUserPwdSecret(), request.getIv());
        //?????????????????????????????????????????????
        BigDecimal valueInUnit = request.getValue();
        ParityClientWrapper parity = WalletHolder.getParityClient();
        //????????????
        if (request.getMainCoin() == 1) {
            BigInteger estimateGas = ERC20TokenUtil.getEstimateGas(walletAddress, parity.getEthWallInfo().getColdAddress(), valueInUnit.toBigInteger());
            logger.info("eth?????????????????????????????????estimateGas:{}", estimateGas);
            BigInteger gasInEth = estimateGas.multiply(gasprice);
            logger.info("eth?????????????????????????????????estimateGas ?????????ETH:{}", gasInEth.toString());
            logger.info("??????????????????????????????:{}", JSON.toJSONString(request));
            MexcColdTrans coldTrans = transferToCold(request);
            try {
                //????????????????????????????????????
                BigDecimal actualAmount = valueInUnit.subtract(new BigDecimal(gasInEth));
                logger.info("ETH????????????????????????????????????????????????actualAmount:{}", actualAmount.toPlainString());
                String userToColdTxHash = null;
                try {
                    userToColdTxHash = ERC20TokenUtil.postTransaction(walletAddress, parity.getEthWallInfo().getColdAddress(), estimateGas, actualAmount.toBigInteger(), assetPwd);
                } catch (Exception e) {
                    logger.info("ETH????????????????????????", e);
                    coldTrans.setTxReceipt("ETH????????????????????????");
                    coldTransDelegate.updateByPrimaryKeySelective(coldTrans);
                    return false;
                }
                logger.info("ETH?????????????????????????????????:{}", userToColdTxHash);
                if (StringUtils.isEmpty(userToColdTxHash)) {
                    throw new SystemException(ResultCode.COMMON_ERROR, "?????????????????????????????????");
                }
                try {
                    TransactionReceipt receipt = ERC20TokenUtil.waitForTransactionReceipt(userToColdTxHash, parity);
                    coldTrans.setTxReceipt(JSON.toJSONString(receipt));
                    coldTrans.setGasUsed(new BigDecimal(receipt.getGasUsed()));
                    coldTrans.setTxFee(ERC20TokenUtil.parseAmount(request.getScale(), gasInEth.toString()));
                } catch (Exception e) {
                    coldTrans.setTxReceipt(e.getMessage());
                }
                logger.info("ETH???????????????????????????????????????:{}", JSON.toJSONString(userToColdTxHash));
                coldTrans.setTxHash(userToColdTxHash);
                coldTrans.setGasPrice(new BigDecimal(gasprice));
                logger.info("ETH?????????????????????????????????????????????:{}", JSON.toJSONString(coldTrans));
                coldTransDelegate.updateByPrimaryKeySelective(coldTrans);
            } catch (Exception e) {
                logger.error("?????????????????????????????????", e);
                MailSender.sendWarningMail("?????????????????????????????????" + e.getMessage());
                throw new SystemException(ResultCode.COMMON_ERROR, "?????????????????????????????????");
            }
        } else {
            BigInteger estimateGas = ERC20TokenUtil.getContractTransEstimateGas(walletAddress, parity.getEthWallInfo().getColdAddress(), request.getContractAddress(), valueInUnit.toBigInteger());
            logger.info("??????????????????:{}", estimateGas);
            BigInteger gasInEth = estimateGas.multiply(gasprice);
            logger.info("????????????????????????eth:{}", gasInEth);
            //??????????????????????????????????????????
            logger.info("??????????????????????????????????????????:{}", estimateGas);
            BigInteger ethFeeBalance = ERC20TokenUtil.queryBalance(walletAddress);
            logger.info("???????????????????????????Eth??????:{}", ethFeeBalance.toString());
            if (ethFeeBalance.compareTo(gasInEth) < 0) {
                //????????????????????????
                logger.info("ETH????????????????????????");
                MexcEthHotTrans hotTrans = saveHotWalletFeeTrans(request);
                logger.info("???????????????????????????????????????:{}", JSON.toJSONString(hotTrans));
                //???????????????????????????????????????????????????
                String txHash = null;
                try {
                    txHash = ERC20TokenUtil.postTransaction(parity.getEthWallInfo().getHotAddress(), walletAddress, estimateGas, gasInEth, parity.getEthWallInfo().getHotAddressPwd());
                    hotTrans.setTxHash(txHash);
                } catch (Exception e) {
                    logger.info("ETH?????????????????????", e);
                    hotTrans.setTxReceipt("ETH?????????????????????" + e.getMessage());
                    hotTrans.setStatus(-1);
                    hotTransDelegate.updateByPrimaryKeySelective(hotTrans);
                    return false;
                }
                logger.info("????????????????????????????????????:{}", gasInEth);
                if (StringUtils.isEmpty(txHash)) {
                    throw new SystemException(ResultCode.COMMON_ERROR, "??????????????????????????????");
                }
                //???????????????????????????
                logger.info("??????????????????????????????????????????????????????????????????");
                try {
                    TransactionReceipt feereceipt = ERC20TokenUtil.waitForTransactionReceipt(txHash, parity);
                    hotTrans.setTxReceipt(JSON.toJSONString(feereceipt));
                    hotTrans.setGasPrice(new BigDecimal(gasprice));
                    hotTrans.setGasUsed(new BigDecimal(feereceipt.getGasUsed()));
                    logger.info("?????????????????????????????????????????????????????? feereceipt:{}", JSON.toJSONString(feereceipt));
                    hotTransDelegate.updateByPrimaryKeySelective(hotTrans);
                } catch (Exception e) {
                    logger.error("????????????????????????-->???????????????????????????????????????", e);
                    hotTrans.setTxReceipt("????????????????????????-->???????????????????????????????????????" + e.getMessage());
                    hotTrans.setStatus(-1);
                    hotTransDelegate.updateByPrimaryKeySelective(hotTrans);
                    return false;
                }
                hotTrans.setGasPrice(new BigDecimal(gasprice));
                hotTrans.setTxAmount(ERC20TokenUtil.parseunit(request.getScale(), valueInUnit.toPlainString()));
                hotTrans.setStatus(1);
                hotTransDelegate.updateByPrimaryKeySelective(hotTrans);
            }
            MexcColdTrans coldTrans = saveAssetToClodWalletTrans(request);
            logger.info("??????????????????????????????????????????,?????????????????????");
            //?????????????????????
            TransactionReceipt transreceipt;
            try {
                logger.info("????????????:{},address:{}", JSON.toJSONString(request));
                String pwd = ThressDescUtil.decodeAssetPwd(request.getUserPwdSecret(), request.getIv());
                String transHash = ERC20TokenUtil.sendTransferTokensTransaction(walletAddress, parity.getEthWallInfo().getColdAddress(), request.getContractAddress().toLowerCase(), pwd, request.getValue().toBigInteger(), parity);
                logger.info("??????????????????????????????????????????,?????????????????????-->????????????:{}", JSON.toJSONString(transHash));
                transreceipt = ERC20TokenUtil.getTransReceipt(transHash);
                coldTrans.setGasPrice(new BigDecimal(gasprice));
                coldTrans.setGasUsed(new BigDecimal(transreceipt.getGasUsed()));
                coldTrans.setTxReceipt(JSON.toJSONString(transreceipt));
                coldTrans.setTxFee(new BigDecimal(gasInEth));
                coldTransDelegate.updateByPrimaryKeySelective(coldTrans);
            } catch (Exception e) {
                logger.error("?????????????????????????????????", e);
                coldTrans.setGasPrice(new BigDecimal(gasprice));
                coldTrans.setTxReceipt("?????????????????????????????????" + e.getMessage());
                coldTrans.setStatus(-1);
                coldTransDelegate.updateByPrimaryKeySelective(coldTrans);
                return false;
            }
            if (transreceipt == null) {
                logger.info("??????????????????????????????????????????,?????????????????????-->????????????????????????false");
                return false;
            }
            coldTrans.setStatus(1);
            coldTrans.setTxHash(transreceipt.getTransactionHash());
            logger.info("??????????????????????????????????????????,?????????????????????-->????????????:{}", JSON.toJSONString(coldTrans));
            coldTransDelegate.updateByPrimaryKeySelective(coldTrans);
        }
        return true;
    }


    private MexcColdTrans btcTransferToCold(UserWalletToClodRequest request) {
        MexcColdTrans coldTrans = new MexcColdTrans();
        coldTrans.setId(UUIDUtil.get32UUID());
        coldTrans.setStatus(0);
        coldTrans.setTxType(1);
        coldTrans.setMemberId("*");
        coldTrans.setToAddress(WalletHolder.getBtcdClient().getBtcWallInfo().getColdAddress());
        coldTrans.setFromAddress("*");
        coldTrans.setCreateTim(request.getTransDate());
        coldTrans.setTxToken(request.getMainToken());
        coldTrans.setAssetId("*");
        coldTrans.setTxAmount(request.getValue());
        coldTransDelegate.insertSelective(coldTrans);
        return coldTrans;
    }


    private MexcColdTrans transferToCold(UserWalletToClodRequest request) {
        MexcColdTrans coldTrans = new MexcColdTrans();
        coldTrans.setId(UUIDUtil.get32UUID());
        coldTrans.setStatus(0);
        coldTrans.setTxType(1);
        coldTrans.setMemberId(request.getMemberId());
        coldTrans.setToAddress(WalletHolder.getParityClient().getEthWallInfo().getColdAddress());
        coldTrans.setFromAddress("*");
        coldTrans.setCreateTim(request.getTransDate());
        coldTrans.setTxToken(request.getMainToken());
        coldTrans.setAssetId(request.getAssetId());
        BigDecimal valueUnits = ERC20TokenUtil.parseAmount(request.getScale(), request.getValue().toPlainString());
        coldTrans.setTxAmount(valueUnits);
        coldTransDelegate.insertSelective(coldTrans);
        return coldTrans;
    }

    private MexcEthHotTrans saveHotWalletFeeTrans(UserWalletToClodRequest request) {
        MexcEthHotTrans hotTrans = new MexcEthHotTrans();
        hotTrans.setId(UUIDUtil.get32UUID());
        hotTrans.setAssetId(request.getAssetId());
        hotTrans.setReceiptLink(UUIDUtil.get32UUID());
        hotTrans.setCreateTim(request.getTransDate());
        hotTrans.setFromAddress(WalletHolder.getParityClient().getEthWallInfo().getHotAddress());
        hotTrans.setMemberId(request.getMemberId());
        hotTrans.setStatus(0);
        hotTrans.setTxType(-1);
        hotTrans.setTxAmount(ERC20TokenUtil.parseAmount(request.getScale(), request.getValue().toPlainString()));
        hotTrans.setToAddress(ThressDescUtil.decodeAssetAddress(request.getUserWalletAddressSecret()));
        if (request.getMainCoin() == 1) {
            hotTrans.setTxToken(request.getMainToken());
        } else {
            hotTrans.setTxToken(request.getToken());
        }
        hotTransDelegate.insertSelective(hotTrans);
        return hotTrans;
    }


    public MexcColdTrans saveAssetToClodWalletTrans(UserWalletToClodRequest request) {
        MexcColdTrans coldTrans = new MexcColdTrans();
        coldTrans.setAssetId(request.getAssetId());
        coldTrans.setCreateTim(request.getTransDate());
        coldTrans.setFromAddress(ThressDescUtil.decodeAssetAddress(request.getUserWalletAddressSecret()));
        coldTrans.setToAddress(WalletHolder.getParityClient().getEthWallInfo().getColdAddress());
        coldTrans.setMemberId(request.getMemberId());
        coldTrans.setStatus(0);
        coldTrans.setTxType(1);
        coldTrans.setId(UUIDUtil.get32UUID());
        coldTrans.setTxAmount(ERC20TokenUtil.parseAmount(request.getScale(), request.getValue().toPlainString()));
        coldTransDelegate.insertSelective(coldTrans);
        return coldTrans;
    }


    public MexcMemberAsset queryMemberAsset(String memberId, String vcoinId) {
        return memberAssetDelegate.queryMemberAsset(memberId, vcoinId);
    }

    @Override
    public BigDecimal getTxFee() {
        return btcTxFee;
    }

    @Override
    public int updateCashHash(String cashId, String txid) {
        return assetCashDelegate.updateCashHash(cashId, txid);
    }


    private WalletAccount createWalletAddress(WalletAccount walletAccount, String token, MexcMember mexcMember) {
        String address = "";
        String filePath = "";
        if (token.equalsIgnoreCase(TokenEnum.ETH_COIN.getCode())) {
            walletAccount.setSecret(CommonConstant.secrect);
            logger.info("====>???????????????????????????????????? ??????????????????");
            address = ERC20TokenUtil.createWalletAccountBySecret(walletAccount);
            logger.info("====>???????????????????????????????????? ?????????????????? ??????:" + address);
            try {
                String relFilePath = RandomUtil.randomStr(1, RandomUtil.NUM_CHAR) + File.separator;
                File file = new File(eth_path + relFilePath + "/");
                if (!file.exists()) {
                    file.mkdirs();
                }
                logger.info("????????????????????? ??????????????????");
                String fileName = WalletUtils.generateFullNewWalletFile(walletAccount.getPwd(), file);
                logger.info("????????????????????? ??????????????????????????????==>" + file);
                filePath = file.getPath() + fileName;
            } catch (Exception e) {
                logger.error("??????:" + mexcMember.getId() + "????????????????????????", e);
                MailSender.sendWarningMail("??????:" + mexcMember.getId() + "????????????????????????" + e.getMessage());
            }
        } else if (token.equalsIgnoreCase(TokenEnum.BIT_COIN.getCode())) {
            address = BitServerUtil.createAddress(mexcMember.getAccount());
        }
        walletAccount.setAddress(address);
        walletAccount.setFilePath(filePath);
        return walletAccount;
    }
}
