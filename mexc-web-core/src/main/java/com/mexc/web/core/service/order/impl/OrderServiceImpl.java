package com.mexc.web.core.service.order.impl;

import com.alibaba.fastjson.JSON;
import com.laile.esf.common.exception.BusinessException;
import com.laile.esf.common.exception.ResultCode;
import com.laile.esf.common.exception.SystemException;
import com.laile.esf.common.util.DateUtil;
import com.laile.esf.common.util.RandomUtil;
import com.mexc.common.base.ResultVo;
import com.mexc.common.constant.CommonConstant;
import com.mexc.common.constant.ResCode;
import com.mexc.common.constant.TradingViewConstant;
import com.mexc.common.util.LogUtil;
import com.mexc.common.util.UUIDUtil;
import com.mexc.common.util.jedis.RedisUtil;
import com.mexc.dao.delegate.market.MarketDelegate;
import com.mexc.dao.delegate.member.MemberAssetDelegate;
import com.mexc.dao.delegate.member.MemberDelegate;
import com.mexc.dao.delegate.plat.PlatAssetDelegate;
import com.mexc.dao.delegate.plat.PlatAssetDetailDelegate;
import com.mexc.dao.delegate.vcoin.MexcEnTrustDelegate;
import com.mexc.dao.delegate.vcoin.MexcTradeDelegate;
import com.mexc.dao.delegate.vcoin.MexcTradeDetailDelegate;
import com.mexc.dao.delegate.vcoin.VCoinDelegate;
import com.mexc.dao.delegate.wallet.MexcAssetCashDelegate;
import com.mexc.dao.delegate.wallet.MexcAssetRechargeDelegate;
import com.mexc.dao.delegate.wallet.MexcAssetTransDelegate;
import com.mexc.dao.dto.order.CancelEntrustTradeDto;
import com.mexc.dao.dto.order.EntrustTradeDto;
import com.mexc.dao.dto.order.Match.MatchOrder;
import com.mexc.dao.dto.order.OrderDto;
import com.mexc.dao.dto.order.OrderQueryDto;
import com.mexc.dao.dto.tradingview.TradingViewDto;
import com.mexc.dao.dto.vcoin.UpdateEntrustDto;
import com.mexc.dao.javaenum.EntrustOrderEnum;
import com.mexc.dao.javaenum.TradeOrderEnum;
import com.mexc.dao.model.market.MexcMarket;
import com.mexc.dao.model.member.MexcAssetTrans;
import com.mexc.dao.model.member.MexcMember;
import com.mexc.dao.model.member.MexcMemberAsset;
import com.mexc.dao.model.plat.MexcPlatAsset;
import com.mexc.dao.model.plat.MexcPlatAssetDetail;
import com.mexc.dao.model.vcoin.*;
import com.mexc.dao.model.wallet.MexcAssetCash;
import com.mexc.dao.model.wallet.MexcAssetCashVcoin;
import com.mexc.dao.model.wallet.MexcAssetRecharge;
import com.mexc.dao.vo.order.HistoryTradeOrderVo;
import com.mexc.dao.vo.tradingview.TradingViewDataVo;
import com.mexc.dao.vo.tradingview.TradingViewVo;
import com.mexc.match.engine.service.IExchangeMatchService;
import com.mexc.match.engine.util.QueueKeyUtil;
import com.mexc.mq.core.IMqProducerService;
import com.mexc.mq.util.FastJsonMessageConverter;
import com.mexc.mq.vo.MqMsgVo;
import com.mexc.vcoin.TokenEnum;
import com.mexc.web.core.service.order.IOrderService;
import com.mexc.web.core.service.order.IUserEntrustOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;


@Service
public class OrderServiceImpl implements IOrderService {

    private static Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Resource
    MexcEnTrustDelegate mexcEnTrustDelegate;

    @Resource
    MarketDelegate marketDelegate;

    @Resource
    MexcTradeDelegate mexcTradeDelegate;

    @Resource
    MexcTradeDetailDelegate mexcTradeDetailDelegate;

    @Resource
    MemberDelegate memberDelegate;

    @Resource
    MemberAssetDelegate memberAssetDelegate;

    @Resource
    VCoinDelegate vCoinDelegate;

    @Resource
    MexcAssetTransDelegate mexcAssetTransDelegate;

    @Resource
    IMqProducerService mqProducerService;

    @Resource
    FastJsonMessageConverter fastJsonMessageConverter;

    @Resource
    PlatAssetDelegate platAssetDelegate;

    @Resource
    PlatAssetDetailDelegate platAssetDetailDelegate;

    @Resource
    MexcAssetRechargeDelegate mexcAssetRechargeDelegate;

    @Resource
    MexcAssetCashDelegate mexcAssetCashDelegate;

    @Resource
    IUserEntrustOrderService userEntrustOrderService;

    @Autowired
    IExchangeMatchService exchangeMatchService;


    @Value("${mq.queue.btc.market.trade}")
    private String btcQueue;

    @Value("${mq.queue.eth.market.trade}")
    private String ethQueue;


    public MexcEnTrust queryEntrust(String tradeNo) {
        return mexcEnTrustDelegate.queryEntrust(tradeNo);
    }

    public List<OrderDto> queryEntrustOrder(OrderQueryDto queryDto) {
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(queryDto.getAccount());
        queryDto.setMemberId(mexcMember.getId());
        return mexcEnTrustDelegate.queryOrderList(queryDto);
    }

    @Override
    public List<OrderDto> queryEntrustOrderLimit(OrderQueryDto queryDto, Integer limit) {
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(queryDto.getAccount());
        queryDto.setMemberId(mexcMember.getId());
        return mexcEnTrustDelegate.queryOrderLimit(queryDto, limit);
    }

    @Override
    public List<HistoryTradeOrderVo> queryTradeOrder(OrderQueryDto queryDto) {
        MexcMember mexcMember = memberDelegate.queryMermberByAccount(queryDto.getAccount());
        queryDto.setMemberId(mexcMember.getId());
        return mexcTradeDelegate.queryTradeOrder(queryDto);
    }

    @Transactional
    public void doMatchAndUpdate(MatchOrder entrustOrder, ResultVo<List<MatchOrder>> resultVo) {
        /** ??????????????????????????????????????????*/
        List<MatchOrder> matchOrderList = resultVo.getData();
        if (!CollectionUtils.isEmpty(matchOrderList)) {
            this.updateEntrustAndMatchOrderInfo(entrustOrder, matchOrderList);
        }
    }

    /**
     * ?????? ????????? ???????????? ?????????????????????
     *
     * @param entrustOrder
     * @param matchOrderList
     */
    public void updateEntrustAndMatchOrderInfo(MatchOrder entrustOrder, List<MatchOrder> matchOrderList) {
        logger.info(LogUtil.msg("OrderServiceImpl:updateMatchEntrustTradeInfo", "??????????????????????????????..."));
        try {
            List<MatchOrder> processMatchOrderList = new ArrayList<>();
            List<MexcTradeDetail> tradeDetails = new ArrayList<>();

            String tradeId = UUIDUtil.get32UUID();

            /**????????????????????????**/
            for (MatchOrder matchOrder : matchOrderList) {
                logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????" + JSON.toJSONString(matchOrder)));
                MexcMarket market = marketDelegate.selectByPrimaryKey(matchOrder.getMarketId());
                MexcMember member = memberDelegate.selectByPrimaryKey(matchOrder.getMemberId());
                MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(matchOrder.getVcoinId());

                /** ???????????????,???????????????????????? **/
                MexcVCoinFee tradeVCoinFee = vCoinDelegate.queryVCoinFee(vCoin.getId());//???????????????
                MexcVCoinFee mainVCoinFee = vCoinDelegate.queryVCoinFee(market.getVcoinId());//?????????????????????

                BigDecimal buyOrSellfeeRate = new BigDecimal("0");//????????????
                BigDecimal buyOrSellfee = new BigDecimal("0");//?????????
                if (matchOrder.getTradeType() == CommonConstant.BUY) {
                    buyOrSellfeeRate = tradeVCoinFee.getBuyRate();//?????????
                    buyOrSellfee = matchOrder.getTradedNumber().multiply(buyOrSellfeeRate);
                } else if (matchOrder.getTradeType() == CommonConstant.SELL) {
                    buyOrSellfeeRate = mainVCoinFee.getSellRate();//?????????
                    BigDecimal sellAmount = matchOrder.getTradedNumber().multiply(matchOrder.getPrice());
                    buyOrSellfee = sellAmount.multiply(buyOrSellfeeRate);
                }
                processMatchOrderList.add(matchOrder);

                /** ????????????????????????????????????????????? **/
                if (entrustOrder.getTradeNo().equals(matchOrder.getTradeNo())) {
                    MexcTrade mexcTrade = new MexcTrade();
                    mexcTrade.setId(tradeId);
                    mexcTrade.setTradeNumber(matchOrder.getTradedNumber());
                    mexcTrade.setTradeTotalAmount(matchOrder.getPrice().multiply(matchOrder.getTradedNumber()));
                    mexcTrade.setMarketId(matchOrder.getMarketId());
                    mexcTrade.setMarketName(market.getMarketName());
                    mexcTrade.setStatus(TradeOrderEnum.TRADE.getStatus());
                    mexcTrade.setMemberId(matchOrder.getMemberId());
                    mexcTrade.setTradeType(matchOrder.getTradeType());
                    mexcTrade.setTradeVcoinId(matchOrder.getVcoinId());
                    mexcTrade.setTradelVcoinNameEn(vCoin.getVcoinNameEn());
                    mexcTrade.setTradeNo(matchOrder.getTradeNo());
                    mexcTrade.setTradePrice(matchOrder.getPrice());
                    mexcTrade.setCreateBy(member.getId());
                    mexcTrade.setCreateByName(member.getAccount());
                    mexcTrade.setCreateTime(DateUtil.format(new Date(), DateUtil.YYYY_MM_DD_HH_MM_SS));
                    mexcTrade.setTradeRate(buyOrSellfeeRate);
                    mexcTrade.setTradeFee(buyOrSellfee);
                    mexcTradeDelegate.insert(mexcTrade);
                    logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"));
                }


                /** ??????????????????????????????????????????????????????:??????????????????????????????????????? **/
                if (!matchOrder.getTradeNo().equals(entrustOrder.getTradeNo())) {
                    MexcTradeDetail tradeDetail = new MexcTradeDetail();
                    tradeDetail.setId(UUIDUtil.get32UUID());
                    tradeDetail.setMarketId(market.getId());
                    tradeDetail.setCreateBy(member.getId());
                    tradeDetail.setCreateByName(member.getAccount());
                    tradeDetail.setCreateTime(new Date());
                    tradeDetail.setTradeNumber(matchOrder.getTradedNumber());
                    tradeDetail.setMemberId(member.getId());
                    tradeDetail.setTransNo(matchOrder.getTradeNo());
                    tradeDetail.setTradeVcoinId(matchOrder.getVcoinId());
                    tradeDetail.setTradeTotalAmount(matchOrder.getPrice().multiply(matchOrder.getTradedNumber()));
                    tradeDetail.setTradeNo(entrustOrder.getTradeNo());
                    tradeDetail.setTradePrice(matchOrder.getPrice());
                    tradeDetail.setType(1);
                    tradeDetail.setTradeRate(buyOrSellfeeRate);
                    tradeDetail.setTradeFee(buyOrSellfee);
                    tradeDetail.setTradeId(tradeId);
                    tradeDetails.add(tradeDetail);
                    logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????????????????:???????????????????????????????????????"));
                }
            }

            if (org.apache.commons.collections.CollectionUtils.isNotEmpty(processMatchOrderList)) {
                for (MatchOrder matchOrder : processMatchOrderList) {
                    /** ?????????????????? */
                    updateMatchOrder(entrustOrder, matchOrder);
                }
            }
            mexcTradeDetailDelegate.insertBatch(tradeDetails);
        } catch (Exception e) {
            logger.error("??????????????????", e);
            throw new SystemException(ResultCode.COMMON_ERROR, "??????????????????");
        }
    }

    public void updateMatchOrder(MatchOrder entrustOrder, MatchOrder matchOrder) {
        MexcMarket market = marketDelegate.selectByPrimaryKey(matchOrder.getMarketId());
        MexcMember member = memberDelegate.selectByPrimaryKey(matchOrder.getMemberId());
        MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(matchOrder.getVcoinId());

        /** ???????????????,???????????????????????? **/
        MexcVCoinFee tradeVCoinFee = vCoinDelegate.queryVCoinFee(vCoin.getId());//???????????????
        MexcVCoinFee mainVCoinFee = vCoinDelegate.queryVCoinFee(market.getVcoinId());//?????????????????????

        BigDecimal buyOrSellfeeRate = new BigDecimal("0");//????????????
        BigDecimal buyOrSellfee = new BigDecimal("0");//?????????
        if (matchOrder.getTradeType() == CommonConstant.BUY) {
            buyOrSellfeeRate = tradeVCoinFee.getBuyRate();//?????????
            buyOrSellfee = matchOrder.getTradedNumber().multiply(buyOrSellfeeRate);
        } else if (matchOrder.getTradeType() == CommonConstant.SELL) {
            buyOrSellfeeRate = mainVCoinFee.getSellRate();//?????????
            BigDecimal sellAmount = matchOrder.getTradedNumber().multiply(matchOrder.getPrice());
            buyOrSellfee = sellAmount.multiply(buyOrSellfeeRate);
        }


        /** ????????? ???????????????*/
        if (!entrustOrder.getTradeNo().equals(matchOrder.getTradeNo())) {
            /** ???????????????????????????????????? **/
            MexcTrade mexcTrade = new MexcTrade();
            String tradeId = UUIDUtil.get32UUID();
            mexcTrade.setId(tradeId);
            mexcTrade.setTradeNumber(matchOrder.getTradedNumber());
            mexcTrade.setTradeTotalAmount(matchOrder.getPrice().multiply(matchOrder.getTradedNumber()));
            mexcTrade.setMarketId(matchOrder.getMarketId());
            mexcTrade.setMarketName(market.getMarketName());
            mexcTrade.setStatus(TradeOrderEnum.TRADE.getStatus());
            mexcTrade.setMemberId(matchOrder.getMemberId());
            mexcTrade.setTradeType(matchOrder.getTradeType());
            mexcTrade.setTradeVcoinId(matchOrder.getVcoinId());
            mexcTrade.setTradelVcoinNameEn(vCoin.getVcoinNameEn());
            mexcTrade.setTradeNo(matchOrder.getTradeNo());
            mexcTrade.setTradePrice(matchOrder.getPrice());
            mexcTrade.setCreateBy(member.getId());
            mexcTrade.setCreateByName(member.getAccount());
            mexcTrade.setCreateTime(DateUtil.format(new Date(), DateUtil.YYYY_MM_DD_HH_MM_SS));
            mexcTrade.setTradeRate(buyOrSellfeeRate);
            mexcTrade.setTradeFee(buyOrSellfee);
            mexcTradeDelegate.insert(mexcTrade);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"));

            /**???????????????*/
            MexcTradeDetail tradeDetail = new MexcTradeDetail();
            tradeDetail.setId(UUIDUtil.get32UUID());
            tradeDetail.setMarketId(market.getId());
            tradeDetail.setCreateBy(member.getId());
            tradeDetail.setCreateByName(member.getAccount());
            tradeDetail.setCreateTime(new Date());
            tradeDetail.setTradeNumber(matchOrder.getTradedNumber());
            tradeDetail.setMemberId(member.getId());
            tradeDetail.setTransNo(entrustOrder.getTradeNo());
            tradeDetail.setTradeVcoinId(matchOrder.getVcoinId());
            tradeDetail.setTradeTotalAmount(matchOrder.getPrice().multiply(matchOrder.getTradedNumber()));
            tradeDetail.setTradeNo(matchOrder.getTradeNo());
            tradeDetail.setTradePrice(matchOrder.getPrice());
            tradeDetail.setType(1);
            tradeDetail.setTradeRate(buyOrSellfeeRate);
            tradeDetail.setTradeFee(buyOrSellfee);
            tradeDetail.setTradeId(tradeId);
            mexcTradeDetailDelegate.insert(tradeDetail);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????????????????:???????????????????????????????????????"));
        }


        /** ???????????????????????????????????? **/
        MexcEnTrust mexcEnTrust = mexcEnTrustDelegate.queryEntrust(matchOrder.getTradeNo());
        if (mexcEnTrust == null) {
            logger.error("?????????????????????,entrust:{}", JSON.toJSONString(mexcEnTrust));
            throw new BusinessException(ResultCode.COMMON_ERROR, "?????????????????????.");
        }


        /** ??????????????? */
        UpdateEntrustDto updateEntrustDto = new UpdateEntrustDto();
        updateEntrustDto.setId(mexcEnTrust.getId());
        updateEntrustDto.setTradeNo(matchOrder.getTradeNo());
        updateEntrustDto.setTradedNumber(matchOrder.getTradedNumber());
        updateEntrustDto.setTradedAmount(matchOrder.getTradedNumber().multiply(matchOrder.getPrice()));
        updateEntrustDto.setUpdateByName(member.getAccount());
        updateEntrustDto.setUpdateBy(member.getId());
        updateEntrustDto.setUpdateTime(new Date());

        if (matchOrder.getTradedNumber().compareTo(matchOrder.getTradableNumber()) == 0) {//????????????
            updateEntrustDto.setStatus(EntrustOrderEnum.COMPLETED.getStatus());
        } else if (matchOrder.getTradedNumber().compareTo(matchOrder.getTradableNumber()) < 0) {//????????????
            updateEntrustDto.setStatus(EntrustOrderEnum.PART_COMPLETED.getStatus());//??????
        }

        int rows = mexcEnTrustDelegate.updateEntrustInfo(updateEntrustDto);
        if (rows < 1) {
            logger.error("????????????????????????,??????????????????????????????,entrust:{}", JSON.toJSONString(mexcEnTrust));
            throw new BusinessException(ResultCode.COMMON_ERROR, "????????????????????????,????????????????????????????????????.");
        }

        /**??????????????????????????????**/
        if (matchOrder.getTradedNumber().compareTo(matchOrder.getTradableNumber()) == 0) {//????????????
            userEntrustOrderService.deleteCurrentEntrustOrderCache(mexcEnTrust);//??????????????????????????????
        } else if (matchOrder.getTradedNumber().compareTo(matchOrder.getTradableNumber()) < 0) {//????????????
            userEntrustOrderService.updateCurrentEntrustOrderCache(mexcEnTrust);//??????????????????????????????
        }
        logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????????????????"));


        /**??????????????????????????????????????????????????????**/
        MexcMemberAsset mainVcoinAsset = memberAssetDelegate.queryMemberAsset(matchOrder.getMemberId(), market.getVcoinId());//??????????????????
        MexcMemberAsset currentVcoinAsset = memberAssetDelegate.queryMemberAsset(matchOrder.getMemberId(), vCoin.getId());//?????????????????????
        if (matchOrder.getTradeType() == CommonConstant.BUY) {
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????" + JSON.toJSONString(matchOrder)));

            BigDecimal tradeAmount = matchOrder.getPrice().multiply(matchOrder.getTradedNumber());//??????????????????????????? * ????????????
            memberAssetDelegate.unfrozenAmount(mainVcoinAsset.getId(), tradeAmount);//??????
            BigDecimal outAmount = matchOrder.getTradedNumber().multiply(matchOrder.getPrice());//???????????????????????? * ????????????
            BigDecimal inAmount = matchOrder.getTradedNumber().subtract(buyOrSellfee);//?????? : ???????????? - ?????????

            memberAssetDelegate.assetOutcomeing(mainVcoinAsset.getId(), outAmount);//??????
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????"));
            memberAssetDelegate.assetIncomeing(currentVcoinAsset.getId(), inAmount);//??????
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????"));


            //???????????? :???????????????????????????????????????
            MexcAssetTrans feeTrans = new MexcAssetTrans();
            feeTrans.setAssetId(currentVcoinAsset.getId());
            feeTrans.setTradeType("3");//????????? ??? -1:?????? 1:?????? 2:?????? 3:????????? 4??????????????? 5:????????????
            feeTrans.setTransNo(matchOrder.getTradeNo());
            feeTrans.setTransAmount(buyOrSellfee);
            feeTrans.setTransType(-1);//??????
            feeTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(feeTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????"));

            //??????????????????
            MexcAssetTrans assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(mainVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(matchOrder.getTradeNo());
            assetTrans.setTransAmount(outAmount);
            assetTrans.setTransType(-1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????"));

            //????????????
            assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(currentVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(matchOrder.getTradeNo());
            assetTrans.setTransAmount(inAmount);
            assetTrans.setTransType(1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"));

            //????????????????????????
            try {
                MexcPlatAsset platAsset = platAssetDelegate.queryPlatAsset(matchOrder.getVcoinId());
                platAssetDelegate.assetIncome(platAsset.getId(), buyOrSellfee);
                logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????????????????"));
                //??????????????????????????????
                MexcPlatAssetDetail platAssetDetail = new MexcPlatAssetDetail();
                platAssetDetail.setAmount(buyOrSellfee);
                platAssetDetail.setPlatId(platAsset.getId());
                platAssetDetail.setOptTime(new Date());
                platAssetDetail.setOptType(1);
                platAssetDetail.setBalance(buyOrSellfee);
                platAssetDetail.setTradeFee(buyOrSellfee);
                platAssetDetail.setTradeRate(buyOrSellfeeRate);
                platAssetDetailDelegate.insert(platAssetDetail);
            } catch (Exception e) {
                logger.error(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"), e);
            }

            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????"));

        } else if (matchOrder.getTradeType() == CommonConstant.SELL) {
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????" + JSON.toJSONString(matchOrder)));
            BigDecimal inAmount = matchOrder.getTradedNumber().multiply(matchOrder.getPrice());//??????????????????????????? * ?????????
            BigDecimal outAmount = matchOrder.getTradedNumber();//???????????????????????????
            BigDecimal actualInAmount = inAmount.subtract(buyOrSellfee);//???????????????????????????????????????-?????????

            memberAssetDelegate.unfrozenAmount(currentVcoinAsset.getId(), outAmount);//??????
            memberAssetDelegate.assetOutcomeing(currentVcoinAsset.getId(), outAmount);//??????
            memberAssetDelegate.assetIncomeing(mainVcoinAsset.getId(), actualInAmount);//??????
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????"));

            //???????????? :???????????????????????????????????????
            MexcAssetTrans feeTrans = new MexcAssetTrans();
            feeTrans.setAssetId(mainVcoinAsset.getId());
            feeTrans.setTradeType("3");//????????? ??? -1:?????? 1:?????? 2:?????? 3:????????? 4??????????????? 5:????????????
            feeTrans.setTransNo(matchOrder.getTradeNo());
            feeTrans.setTransAmount(buyOrSellfee);
            feeTrans.setTransType(-1);//??????
            feeTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(feeTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????"));

            //??????????????????
            MexcAssetTrans assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(mainVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(matchOrder.getTradeNo());
            assetTrans.setTransAmount(actualInAmount);
            assetTrans.setTransType(1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????"));
            //????????????
            assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(currentVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(matchOrder.getTradeNo());
            assetTrans.setTransAmount(outAmount);
            assetTrans.setTransType(-1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????????????????"));
            //????????????????????????
            try {
                MexcPlatAsset platAsset = platAssetDelegate.queryPlatAsset(matchOrder.getVcoinId());
                platAssetDelegate.assetIncome(platAsset.getId(), buyOrSellfee);
                logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????????????????"));
                //??????????????????????????????
                MexcPlatAssetDetail platAssetDetail = new MexcPlatAssetDetail();
                platAssetDetail.setAmount(buyOrSellfee);
                platAssetDetail.setPlatId(platAsset.getId());
                platAssetDetail.setOptTime(new Date());
                platAssetDetail.setOptType(1);
                platAssetDetail.setBalance(buyOrSellfee);
                platAssetDetail.setTradeFee(buyOrSellfee);
                platAssetDetail.setTradeRate(buyOrSellfeeRate);
                platAssetDetailDelegate.insert(platAssetDetail);
            } catch (Exception e) {
                logger.error(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"), e);
            }
        }
    }

    /**
     * ????????????
     *
     * @param tradeDto
     */
    @Transactional
    public void handEntrustTrade(EntrustTradeDto tradeDto) {
        MexcMarket market = marketDelegate.selectByPrimaryKey(tradeDto.getMarketId());
        MexcMember member = memberDelegate.queryMermberByAccount(tradeDto.getAccount());
        MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(tradeDto.getVcoinId());

        MexcMemberAsset mainVcoinAsset = memberAssetDelegate.queryMemberAsset(member.getId(), market.getVcoinId());//??????????????????
        MexcMemberAsset currentVcoinAsset = memberAssetDelegate.queryMemberAsset(member.getId(), vCoin.getId());//?????????????????????

        /** ???????????? */
        BigDecimal tradeAmount = new BigDecimal(tradeDto.getTradeNumber()).multiply(new BigDecimal(tradeDto.getTradePrice()));

        /** ???????????? **/
        if (tradeDto.getTradeType() == CommonConstant.BUY) {
            if (tradeAmount.compareTo(mainVcoinAsset.getBalanceAmount()) > 0) {
                logger.info("OrderServiceImpl-> entrustTrade???????????????");
                throw new BusinessException(ResultCode.COMMON_ERROR, "????????????");
            }
        } else if (tradeDto.getTradeType() == CommonConstant.SELL) {
            if (new BigDecimal(tradeDto.getTradeNumber()).compareTo(currentVcoinAsset.getBalanceAmount()) > 0) {
                logger.info("OrderServiceImpl->entrustTrade???????????????");
                throw new BusinessException(ResultCode.COMMON_ERROR, "????????????");
            }
        }

        /** ?????????????????? **/
        String entrustTime = DateUtil.format(new Date(), DateUtil.YYYY_MM_DD_HH_MM_SS);
        MatchOrder mainOrder = new MatchOrder();
        String tradeNo = UUIDUtil.get32UUID();
        mainOrder.setTradeNo(tradeNo);
        mainOrder.setEntrustNumber(new BigDecimal(tradeDto.getTradeNumber()));
        mainOrder.setTradeType(tradeDto.getTradeType());
        mainOrder.setMarketId(market.getId());
        mainOrder.setPrice(new BigDecimal(tradeDto.getTradePrice()));
        mainOrder.setTradedNumber(new BigDecimal("0"));
        mainOrder.setTradableNumber(new BigDecimal(tradeDto.getTradeNumber()));
        mainOrder.setVcoinId(vCoin.getId());
        mainOrder.setMemberId(member.getId());
        mainOrder.setEntrustTime(entrustTime);

        /** ?????????????????? */
        ResultVo<List<MatchOrder>> resultVo = exchangeMatchService.match(mainOrder);
        if (!ResCode.SUCCESS.equals(resultVo.getCode()) || resultVo.getData() == null) {
            throw new BusinessException(ResultCode.COMMON_ERROR, "??????????????????????????????????????????");
        }


        List<MatchOrder> matchOrderList = resultVo.getData();

        /** ??????????????? */
        updateMainOrder(mainOrder, matchOrderList);

        /** ?????????????????? */
        for (MatchOrder matchOrder : matchOrderList) {
            if (!matchOrder.getTradeNo().equals(tradeNo)) {
                updateMatchOrder(mainOrder, matchOrder);
            }
        }
    }

    @Transactional
    public void updateMainOrder(MatchOrder mainOrder, List<MatchOrder> matchOrderList) {
        if (mainOrder.getTradedNumber().compareTo(mainOrder.getTradableNumber()) < 0) {
            throw new BusinessException(ResultCode.COMMON_ERROR, "??????????????????????????????????????????????????????");
        }

        MexcMarket market = marketDelegate.selectByPrimaryKey(mainOrder.getMarketId());
        MexcMember member = memberDelegate.selectByPrimaryKey(mainOrder.getMemberId());
        MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(mainOrder.getVcoinId());

        /** ???????????????,???????????????????????? **/
        MexcVCoinFee tradeVCoinFee = vCoinDelegate.queryVCoinFee(vCoin.getId());//???????????????
        MexcVCoinFee mainVCoinFee = vCoinDelegate.queryVCoinFee(market.getVcoinId());//?????????????????????

        BigDecimal buyOrSellfeeRate = new BigDecimal("0");//????????????
        BigDecimal buyOrSellfee = new BigDecimal("0");//?????????
        if (mainOrder.getTradeType() == CommonConstant.BUY) {
            buyOrSellfeeRate = tradeVCoinFee.getBuyRate();//?????????
            buyOrSellfee = mainOrder.getTradedNumber().multiply(buyOrSellfeeRate);
        } else if (mainOrder.getTradeType() == CommonConstant.SELL) {
            buyOrSellfeeRate = mainVCoinFee.getSellRate();//?????????
            BigDecimal sellAmount = mainOrder.getTradedNumber().multiply(mainOrder.getPrice());
            buyOrSellfee = sellAmount.multiply(buyOrSellfeeRate);
        }

        /** ???????????????????????????????????? **/
        MexcTrade mexcTrade = new MexcTrade();
        String tradeId = UUIDUtil.get32UUID();
        mexcTrade.setId(tradeId);
        mexcTrade.setTradeNumber(mainOrder.getTradedNumber());
        mexcTrade.setTradeTotalAmount(mainOrder.getPrice().multiply(mainOrder.getTradedNumber()));
        mexcTrade.setMarketId(mainOrder.getMarketId());
        mexcTrade.setMarketName(market.getMarketName());
        mexcTrade.setStatus(TradeOrderEnum.TRADE.getStatus());
        mexcTrade.setMemberId(mainOrder.getMemberId());
        mexcTrade.setTradeType(mainOrder.getTradeType());
        mexcTrade.setTradeVcoinId(mainOrder.getVcoinId());
        mexcTrade.setTradelVcoinNameEn(vCoin.getVcoinNameEn());
        mexcTrade.setTradeNo(mainOrder.getTradeNo());
        mexcTrade.setTradePrice(mainOrder.getPrice());
        mexcTrade.setCreateBy(member.getId());
        mexcTrade.setCreateByName(member.getAccount());
        mexcTrade.setCreateTime(DateUtil.format(new Date(), DateUtil.YYYY_MM_DD_HH_MM_SS));
        mexcTrade.setTradeRate(buyOrSellfeeRate);
        mexcTrade.setTradeFee(buyOrSellfee);
        mexcTradeDelegate.insert(mexcTrade);
        logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"));

        /** ???????????????????????? */
        for (MatchOrder matchOrder : matchOrderList) {
            if (!mainOrder.getTradeNo().equals(mainOrder.getTradeNo())) {
                MexcTradeDetail tradeDetail = new MexcTradeDetail();
                tradeDetail.setId(UUIDUtil.get32UUID());
                tradeDetail.setMarketId(market.getId());
                tradeDetail.setCreateBy(member.getId());
                tradeDetail.setCreateByName(member.getAccount());
                tradeDetail.setCreateTime(new Date());
                tradeDetail.setTradeNumber(matchOrder.getTradedNumber());
                tradeDetail.setMemberId(member.getId());
                tradeDetail.setTransNo(matchOrder.getTradeNo());
                tradeDetail.setTradeVcoinId(matchOrder.getVcoinId());
                tradeDetail.setTradeTotalAmount(matchOrder.getPrice().multiply(matchOrder.getTradedNumber()));
                tradeDetail.setTradeNo(mainOrder.getTradeNo());
                tradeDetail.setTradePrice(matchOrder.getPrice());
                tradeDetail.setType(1);
                tradeDetail.setTradeRate(buyOrSellfeeRate);
                tradeDetail.setTradeFee(buyOrSellfee);
                tradeDetail.setTradeId(tradeId);
                mexcTradeDetailDelegate.insert(tradeDetail);
            }
            logger.info(LogUtil.msg("OrderServiceImpl:updateMainOrder", "?????????????????????????????????:???????????????????????????????????????"));
        }


        /**??????????????????????????????????????????????????????**/
        MexcMemberAsset mainVcoinAsset = memberAssetDelegate.queryMemberAsset(mainOrder.getMemberId(), market.getVcoinId());//??????????????????
        MexcMemberAsset currentVcoinAsset = memberAssetDelegate.queryMemberAsset(mainOrder.getMemberId(), vCoin.getId());//?????????????????????
        if (mainOrder.getTradeType() == CommonConstant.BUY) {
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????" + JSON.toJSONString(mainOrder)));

            BigDecimal outAmount = mainOrder.getTradedNumber().multiply(mainOrder.getPrice());//???????????????????????? * ????????????
            BigDecimal inAmount = mainOrder.getTradedNumber().subtract(buyOrSellfee);//?????? : ???????????? - ?????????

            memberAssetDelegate.assetOutcomeing(mainVcoinAsset.getId(), outAmount);//??????
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????"));
            memberAssetDelegate.assetIncomeing(currentVcoinAsset.getId(), inAmount);//??????
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????"));


            //???????????? :???????????????????????????????????????
            MexcAssetTrans feeTrans = new MexcAssetTrans();
            feeTrans.setAssetId(currentVcoinAsset.getId());
            feeTrans.setTradeType("3");//????????? ??? -1:?????? 1:?????? 2:?????? 3:????????? 4??????????????? 5:????????????
            feeTrans.setTransNo(mainOrder.getTradeNo());
            feeTrans.setTransAmount(buyOrSellfee);
            feeTrans.setTransType(-1);//??????
            feeTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(feeTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????"));

            //??????????????????
            MexcAssetTrans assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(mainVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(mainOrder.getTradeNo());
            assetTrans.setTransAmount(outAmount);
            assetTrans.setTransType(-1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????"));

            //????????????
            assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(currentVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(mainOrder.getTradeNo());
            assetTrans.setTransAmount(inAmount);
            assetTrans.setTransType(1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"));

            //????????????????????????
            try {
                MexcPlatAsset platAsset = platAssetDelegate.queryPlatAsset(mainOrder.getVcoinId());
                platAssetDelegate.assetIncome(platAsset.getId(), buyOrSellfee);
                logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????????????????"));
                //??????????????????????????????
                MexcPlatAssetDetail platAssetDetail = new MexcPlatAssetDetail();
                platAssetDetail.setAmount(buyOrSellfee);
                platAssetDetail.setPlatId(platAsset.getId());
                platAssetDetail.setOptTime(new Date());
                platAssetDetail.setOptType(1);
                platAssetDetail.setBalance(buyOrSellfee);
                platAssetDetail.setTradeFee(buyOrSellfee);
                platAssetDetail.setTradeRate(buyOrSellfeeRate);
                platAssetDetailDelegate.insert(platAssetDetail);
            } catch (Exception e) {
                logger.error(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"), e);
            }

            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????"));

        } else if (mainOrder.getTradeType() == CommonConstant.SELL) {
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????" + JSON.toJSONString(mainOrder)));
            BigDecimal inAmount = mainOrder.getTradedNumber().multiply(mainOrder.getPrice());//??????????????????????????? * ?????????
            BigDecimal outAmount = mainOrder.getTradedNumber();//???????????????????????????
            BigDecimal actualInAmount = inAmount.subtract(buyOrSellfee);//???????????????????????????????????????-?????????

            memberAssetDelegate.assetOutcomeing(currentVcoinAsset.getId(), outAmount);//??????
            memberAssetDelegate.assetIncomeing(mainVcoinAsset.getId(), actualInAmount);//??????
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "?????????????????????"));

            //???????????? :???????????????????????????????????????
            MexcAssetTrans feeTrans = new MexcAssetTrans();
            feeTrans.setAssetId(mainVcoinAsset.getId());
            feeTrans.setTradeType("3");//????????? ??? -1:?????? 1:?????? 2:?????? 3:????????? 4??????????????? 5:????????????
            feeTrans.setTransNo(mainOrder.getTradeNo());
            feeTrans.setTransAmount(buyOrSellfee);
            feeTrans.setTransType(-1);//??????
            feeTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(feeTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "??????????????????"));

            //??????????????????
            MexcAssetTrans assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(mainVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(mainOrder.getTradeNo());
            assetTrans.setTransAmount(actualInAmount);
            assetTrans.setTransType(1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????"));
            //????????????
            assetTrans = new MexcAssetTrans();
            assetTrans.setAssetId(currentVcoinAsset.getId());
            assetTrans.setTradeType("2");//??????
            assetTrans.setTransNo(mainOrder.getTradeNo());
            assetTrans.setTransAmount(outAmount);
            assetTrans.setTransType(-1);//??????
            assetTrans.setTransTime(new Date());
            mexcAssetTransDelegate.insert(assetTrans);
            logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????????????????"));
            //????????????????????????
            try {
                MexcPlatAsset platAsset = platAssetDelegate.queryPlatAsset(mainOrder.getVcoinId());
                platAssetDelegate.assetIncome(platAsset.getId(), buyOrSellfee);
                logger.info(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "???????????????????????????"));
                //??????????????????????????????
                MexcPlatAssetDetail platAssetDetail = new MexcPlatAssetDetail();
                platAssetDetail.setAmount(buyOrSellfee);
                platAssetDetail.setPlatId(platAsset.getId());
                platAssetDetail.setOptTime(new Date());
                platAssetDetail.setOptType(1);
                platAssetDetail.setBalance(buyOrSellfee);
                platAssetDetail.setTradeFee(buyOrSellfee);
                platAssetDetail.setTradeRate(buyOrSellfeeRate);
                platAssetDetailDelegate.insert(platAssetDetail);
            } catch (Exception e) {
                logger.error(LogUtil.msg("OrderServiceImpl:updateMatchOrderInfo", "????????????????????????"), e);
            }
        }
    }


    /**
     * ????????????/???????????????
     *
     * @param tradeDto
     */
    public void entrustTrade(EntrustTradeDto tradeDto) {
        try {
            MexcMarket market = marketDelegate.selectByPrimaryKey(tradeDto.getMarketId());
            MexcMember member = memberDelegate.queryMermberByAccount(tradeDto.getAccount());
            MexcVCoin vCoin = vCoinDelegate.selectByPrimaryKey(tradeDto.getVcoinId());

            MexcMemberAsset mainVcoinAsset = memberAssetDelegate.queryMemberAsset(member.getId(), market.getVcoinId());//??????????????????
            MexcMemberAsset currentVcoinAsset = memberAssetDelegate.queryMemberAsset(member.getId(), vCoin.getId());//?????????????????????

            /**????????????*/
            BigDecimal tradeAmount = new BigDecimal(tradeDto.getTradeNumber()).multiply(new BigDecimal(tradeDto.getTradePrice()));

            /** ???????????? **/
            if (tradeDto.getTradeType().equals(CommonConstant.BUY.toString())) {
                if (tradeAmount.compareTo(mainVcoinAsset.getBalanceAmount()) > 0) {
                    logger.info("OrderServiceImpl-> entrustTrade???????????????");
                    throw new BusinessException(ResultCode.COMMON_ERROR, "????????????");
                }
            } else if (tradeDto.getTradeType().equals(CommonConstant.SELL.toString())) {
                if (new BigDecimal(tradeDto.getTradeNumber()).compareTo(currentVcoinAsset.getBalanceAmount()) > 0) {
                    logger.info("OrderServiceImpl->entrustTrade???????????????");
                    throw new BusinessException(ResultCode.COMMON_ERROR, "????????????");
                }
            }

            /** ?????????????????????????????????????????? **/
            String entrustTime = DateUtil.format(new Date(), DateUtil.YYYY_MM_DD_HH_MM_SS);
            MexcEnTrust mexcEnTrust = new MexcEnTrust();
            mexcEnTrust.setStatus(EntrustOrderEnum.UNCOMPLETED.getStatus());
            mexcEnTrust.setTradeNo(UUID.randomUUID().toString());
            mexcEnTrust.setTradeVcoinId(vCoin.getId());
            mexcEnTrust.setMemberId(member.getId());
            mexcEnTrust.setCreateBy(member.getId());
            mexcEnTrust.setCreateByName(member.getAccount());
            mexcEnTrust.setCreateTime(entrustTime);
            mexcEnTrust.setMarketId(market.getId());
            mexcEnTrust.setMarketName(market.getMarketName());
            mexcEnTrust.setTradelVcoinNameEn(vCoin.getVcoinNameEn());
            mexcEnTrust.setTradeType(tradeDto.getTradeType());
            mexcEnTrust.setTradePrice(new BigDecimal(tradeDto.getTradePrice()));
            mexcEnTrust.setTradeNumber(new BigDecimal(tradeDto.getTradeNumber()));
            mexcEnTrust.setTradeRemainNumber(new BigDecimal(tradeDto.getTradeNumber()));
            mexcEnTrust.setTradeTotalAmount(tradeAmount);
            mexcEnTrust.setTradeRemainAmount(tradeAmount);
            mexcEnTrust.setTradeRate(new BigDecimal("0"));
            mexcEnTrust.setTradeFee(new BigDecimal("0"));
            String assetId = "";
            //????????? ???????????????
            if (tradeDto.getTradeType() == CommonConstant.BUY) {
                assetId = mainVcoinAsset.getId();
            } else if (tradeDto.getTradeType() == CommonConstant.SELL) {
                assetId = currentVcoinAsset.getId();
            }
            mexcEnTrustDelegate.entrustOrder(mexcEnTrust, assetId);


            /** ?????????????????????????????? **/
            MatchOrder entrustOrder = new MatchOrder();
            entrustOrder.setTradeNo(mexcEnTrust.getTradeNo());
            entrustOrder.setEntrustNumber(mexcEnTrust.getTradeNumber());
            entrustOrder.setTradeType(mexcEnTrust.getTradeType());
            entrustOrder.setMarketId(market.getId());
            entrustOrder.setPrice(mexcEnTrust.getTradePrice());
            entrustOrder.setTradedNumber(new BigDecimal("0"));
            entrustOrder.setTradableNumber(new BigDecimal(tradeDto.getTradeNumber()));
            entrustOrder.setVcoinId(vCoin.getId());
            entrustOrder.setMemberId(member.getId());
            entrustOrder.setEntrustTime(entrustTime);

            MqMsgVo<MatchOrder> mqMsgVo = new MqMsgVo<>();

            mqMsgVo.setMsgId(RandomUtil.randomStr(8))
                    .setContent(entrustOrder)
                    .setInsertTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));

            logger.info(LogUtil.msg("OrderServiceImpl:entrustTrade", "?????????????????????????????????data:" + JSON.toJSONString(mqMsgVo)));
            try {
                if (market.getMarketName().equalsIgnoreCase(TokenEnum.BIT_COIN.getCode())) {
                    mqProducerService.convertAndSend(btcQueue, fastJsonMessageConverter.sendMessage(mqMsgVo));
                } else if (market.getMarketName().equalsIgnoreCase(TokenEnum.ETH_COIN.getCode())) {
                    mqProducerService.convertAndSend(ethQueue, fastJsonMessageConverter.sendMessage(mqMsgVo));
                }
                int result = mexcEnTrustDelegate.updateMqStatus(entrustOrder.getTradeNo());
                if (result < 0) {
                    logger.warn("??????????????????MQ??????:{}", entrustOrder.getTradeNo());
                }
            } catch (Exception e) {
                logger.error(LogUtil.msg("OrderServiceImpl:entrustTrade", "?????????????????????????????????data:" + JSON.toJSONString(mqMsgVo)), e);
            }
            /** ?????????????????????????????? **/
            mexcEnTrustDelegate.updateMqStatus(mexcEnTrust.getTradeNo());
            /** ?????????????????????????????????????????? **/
            userEntrustOrderService.addCurrentEntrustOrderCache(mexcEnTrust);

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("??????????????????", e);
            throw new SystemException(ResultCode.COMMON_ERROR, "??????????????????");
        }
    }


    /**
     * ??????
     *
     * @param cancelEntrustTradeDto
     */
    @Transactional
    public void cancelEntrustTrade(CancelEntrustTradeDto cancelEntrustTradeDto) {
        MexcMember member = memberDelegate.queryMermberByAccount(cancelEntrustTradeDto.getAccount());
        MexcEnTrust enTrust = mexcEnTrustDelegate.queryEntrust(cancelEntrustTradeDto.getTradeNo());

        /** ??????????????????:?????? ?????????????????? ????????????????????????????????? **/
        if (enTrust.getTradeRemainNumber().compareTo(enTrust.getTradeNumber()) < 0) {
            enTrust.setStatus(EntrustOrderEnum.PART_CANCEL.getStatus());
        } else {
            enTrust.setStatus(EntrustOrderEnum.CANCEL.getStatus());
        }
        int rows = mexcEnTrustDelegate.updateEntrustToCancel(enTrust);
        if (rows < 1) {
            logger.error("?????????????????????????????????,??????????????????????????????,entrust:{}", JSON.toJSONString(enTrust));
            throw new BusinessException(ResultCode.COMMON_ERROR, "????????????????????????,???????????????????????????????????????.");
        }

        /** ???????????????????????????**/
        try {
            MexcMarket market = marketDelegate.selectByPrimaryKey(enTrust.getMarketId());
            MexcMemberAsset mainVcoinAsset = memberAssetDelegate.queryMemberAsset(member.getId(), market.getVcoinId());//??????????????????
            MexcMemberAsset currentVcoinAsset = memberAssetDelegate.queryMemberAsset(member.getId(), enTrust.getTradeVcoinId());//?????????????????????

            if (enTrust.getTradeType().intValue() == CommonConstant.BUY.intValue()) {//????????????????????????
                BigDecimal unfrozenAmount = enTrust.getTradeRemainNumber().multiply(enTrust.getTradePrice());
                memberAssetDelegate.unfrozenAmount(mainVcoinAsset.getId(), unfrozenAmount);
            } else if (enTrust.getTradeType().intValue() == CommonConstant.SELL.intValue()) {//????????????????????????
                BigDecimal unfrozenAmount = enTrust.getTradeRemainNumber();
                memberAssetDelegate.unfrozenAmount(currentVcoinAsset.getId(), unfrozenAmount);
            }
        } catch (Exception e) {
            logger.error("???????????????????????????", e);
            throw new BusinessException(ResultCode.COMMON_ERROR, "??????????????????????????????");
        }

        try {
            /** ?????????????????? **/
            MexcTradeDetail cancelTradeDetail = new MexcTradeDetail();
            cancelTradeDetail.setMarketId(enTrust.getMarketId());
            cancelTradeDetail.setCreateBy(member.getId());
            cancelTradeDetail.setCreateByName(member.getAccount());
            cancelTradeDetail.setCreateTime(new Date());
            cancelTradeDetail.setTradeNumber(enTrust.getTradeRemainNumber());
            cancelTradeDetail.setMemberId(member.getId());
            cancelTradeDetail.setTradePrice(enTrust.getTradePrice());
            cancelTradeDetail.setTransNo(enTrust.getTradeNo());
            cancelTradeDetail.setTradeVcoinId(enTrust.getTradeVcoinId());
            cancelTradeDetail.setTradeTotalAmount(enTrust.getTradeRemainAmount());
            cancelTradeDetail.setTradeNo(enTrust.getTradeNo());
            cancelTradeDetail.setTradeRate(new BigDecimal(0));
            cancelTradeDetail.setTradeFee(new BigDecimal(0));
            cancelTradeDetail.setType(2);
            mexcTradeDetailDelegate.insert(cancelTradeDetail);
            logger.info("?????????????????????????????? {}", JSON.toJSONString(cancelTradeDetail));

            /**????????????????????????????????????**/
            userEntrustOrderService.deleteCurrentEntrustOrderCache(enTrust);
        } catch (Exception e) {
            logger.error("???????????????????????????????????????????????????,entrust:{}", JSON.toJSONString(enTrust));
            throw new BusinessException(ResultCode.COMMON_ERROR, "??????????????????????????????????????????.");
        }
    }


    /**
     * ??????K ????????????
     *
     * @param tradingViewDto
     *
     * @return
     */
    public Map<String, Object> getTradingData(TradingViewDto tradingViewDto) {
        logger.debug("TradingViewDto params:{}", JSON.toJSONString(tradingViewDto));
        Map<String, Object> resultMap = new TreeMap<>();
        TradingViewVo tradingViewVo = new TradingViewVo();

        List<Long> t = new ArrayList<>();
        List<Double> o = new ArrayList<>();//????????? (??????)
        List<Double> h = new ArrayList<>();//?????????????????????
        List<Double> l = new ArrayList<>();//?????????(??????)
        List<Double> c = new ArrayList<>();//?????????
        List<Double> v = new ArrayList<>();//????????? (??????)

        Set<String> tradingViewSet = null;
        try {
            String key;
            String resolution = tradingViewDto.getResolution();
            switch (resolution) {
                case "1": {
                    key = TradingViewConstant.TRADING_1_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                case "5": {
                    key = TradingViewConstant.TRADING_5_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                case "15": {
                    key = TradingViewConstant.TRADING_15_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                case "30": {
                    key = TradingViewConstant.TRADING_30_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                case "1h": {
                    key = TradingViewConstant.TRADING_1h_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                case "D": {
                    key = TradingViewConstant.TRADING_1d_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                case "1M": {
                    key = TradingViewConstant.TRADING_1m_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
                default: {
                    key = TradingViewConstant.TRADING_1_PREFIX + QueueKeyUtil.getKey(tradingViewDto.getMarketId(), tradingViewDto.getVcoinId());
                    break;
                }
            }
            tradingViewSet = RedisUtil.zrangeByScore(key, tradingViewDto.getTo(), tradingViewDto.getFrom());
            logger.debug("tradingViewVo:{}", JSON.toJSONString(tradingViewVo));
        } catch (Exception e) {
            logger.error("??????????????????", e);
            resultMap.put("s", "error");
            resultMap.put("errmsg", "redis??????????????????");
        }

        if (CollectionUtils.isEmpty(tradingViewSet)) {
            resultMap.put("s", "no_data");
            return resultMap;
        }

        TradingViewDataVo tradingViewData;
        String[] tradingViewJsonArray = tradingViewSet.toArray(new String[]{});
        for (int index = tradingViewJsonArray.length - 1; index >= 0; index--) {
            tradingViewData = JSON.parseObject(tradingViewJsonArray[index], TradingViewDataVo.class);
            t.add(tradingViewData.getT());
            h.add(tradingViewData.getH());
            l.add(tradingViewData.getL());
            o.add(tradingViewData.getO());
            c.add(tradingViewData.getC());
            v.add(tradingViewData.getV());
        }
        resultMap.put("s", "ok");
        resultMap.put("t", t);
        resultMap.put("c", c);
        resultMap.put("o", o);
        resultMap.put("h", h);
        resultMap.put("l", l);
        resultMap.put("v", v);
        return resultMap;
    }

    @Override
    public List<MexcAssetRecharge> queryAssetRechargeList(String memberId) {
        return mexcAssetRechargeDelegate.queryAssetRecharge(memberId);
    }

    @Override
    public List<MexcAssetCash> queryAssetCashList(String memberId) {
        return mexcAssetCashDelegate.queryAssetCash(memberId);
    }

    @Override
    public List<MexcAssetCashVcoin> queryAssetCashVcoinList(String memberId) {
        return mexcAssetCashDelegate.queryAssetCashVcoin(memberId);
    }

}
