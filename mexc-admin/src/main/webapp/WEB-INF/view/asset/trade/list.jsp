<%@ page contentType="text/html;charset=UTF-8" language="java" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt"%>
<%@ taglib prefix="shiro" uri="http://shiro.apache.org/tags" %>
<%
    String path = request.getContextPath();
    String basePath = request.getScheme() + "://"
            + request.getServerName() + ":" + request.getServerPort()
            + path + "/";
%>
<base href="<%=basePath%>">
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>交易订单</title>
    <meta name="renderer" content="webkit">
    <meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <meta name="apple-mobile-web-app-status-bar-style" content="black">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="format-detection" content="telephone=no">
    <link rel="stylesheet" href="static/layui/css/layui.css" media="all" />
    <link rel="stylesheet" href="//at.alicdn.com/t/font_tnyc012u2rlwstt9.css" media="all" />
    <link rel="stylesheet" href="static/css/user.css" media="all" />
</head>
<body class="childrenBody">
<blockquote class="layui-elem-quote news_search">
    <div class="layui-inline">
        <form id="searchForm" name="searchForm" class="layui-form" action="asset/trade/orderList">
            <input type="hidden" id="currentPage" name="currentPage" value="${page.currentPage}"/>
            <div class="layui-input-inline">
                <input type="text" value="${queryDto.account}" name="account" placeholder="请输入用户名" class="layui-input search_input"/>
            </div>

            <div class="layui-input-inline">
                <input type="text" value="${queryDto.tradeNo}" name="tradeNo" placeholder="请输入单号" class="layui-input search_input"/>
            </div>

            <div class="layui-input-inline">
                <select name="tradeType">
                    <option value=""></option>
                    <c:if test="${queryDto.tradeType==null}">
                        <option value="1">买</option>
                        <option value="2">卖</option>
                    </c:if>
                    <c:if test="${queryDto.tradeType==1}">
                        <option value="1" selected="selected">买</option>
                        <option value="2">卖</option>
                    </c:if>
                    <c:if test="${queryDto.tradeType==2}">
                        <option value="1">买</option>
                        <option value="2" selected="selected">卖</option>
                    </c:if>
                </select>
            </div>

            <div class="layui-input-inline">
                <input type="text" placeholder="开始时间" class="layui-input" id="startTime" value="${queryDto.startTime}" name="startTime">
            </div>

            <div class="layui-input-inline">
                <input type="text" placeholder="结束时间" class="layui-input" id="endTime" value="${queryDto.endTime}" name="endTime">
            </div>

            <a class="layui-btn search_btn">查询</a>
        </form>
    </div>

    <div class="layui-inline">
        <div class="layui-form-mid layui-word-aux"></div>
    </div>
</blockquote>
<div class="layui-form data_list">
    <table class="layui-table">
        <colgroup>
            <col width="10%">
            <col width="10%">
            <col width="5%">
            <col width="10%">
            <col width="5%">
            <col width="10%">
            <col width="5%">
            <col width="15%">
            <col width="10%">
        </colgroup>
        <thead>
            <tr>
                <th>委托单</th>
                <th>用户名</th>
                <th>市场</th>
                <th>单价</th>
                <th>数量</th>
                <th>总额</th>
                <th>类型</th>
                <th>时间</th>
                <th>状态</th>
            </tr>
        </thead>
        <tbody class="data_content">
            <c:forEach var="item" items="${page.resultList}">
                <tr>
                    <td>${item.tradeNo}</td>
                    <td>${item.createByName}</td>
                    <td><span style="color: green;">${item.marketName}</span></td>
                    <td>
                        <fmt:formatNumber type="number" value="${item.tradePrice}" pattern="0.00000000"
                                          maxFractionDigits="8"/>
                    </td>
                    <td>
                        <fmt:formatNumber type="number" value="${item.tradeNumber}" pattern="0.00"
                                          maxFractionDigits="2"/><span style="color: green;">${item.tradelVcoinNameEn}</span>
                    </td>
                    <td>
                        <fmt:formatNumber type="number" value="${item.tradeTotalAmount}" pattern="0.00000000"
                                          maxFractionDigits="8"/>
                    </td>
                    <td>
                        <c:if test="${item.tradeType==1}"><span style="color: red;">买</span></c:if>
                        <c:if test="${item.tradeType==2}"><span style="color: green;">卖</span></c:if>
                    </td>
                    <td>${item.createTime}</td>
                    <td>
                        <c:if test="${item.status==0}"><span style="color: red;">失败</span></c:if>
                        <c:if test="${item.status==1}"><span style="color: green;">成功</span></c:if>
                    </td>

                </tr>
            </c:forEach>

        </tbody>
    </table>
</div>
<div id="page"></div>
<script type="text/javascript" src="static/layui/layui.js"></script>
<script>
    layui.config({
        base : "static/js/"
    }).use(['form','layer','jquery','laypage','laydate'],function() {
        var form = layui.form,
            layer = parent.layer === undefined ? layui.layer : parent.layer,
            laypage = layui.laypage,
            laydate = layui.laydate,
            $ = layui.jquery;

        laypage.render({
            elem: 'page'
            ,count: ${page.totalResult} //数据总数，从服务端得到
            ,curr: ${page.currentPage}
            ,layout: ['count', 'prev', 'page', 'next', 'skip']
            ,jump: function(obj, first){
                $("#currentPage").val(obj.curr);
                //首次不执行
                if(!first){
                    $("#searchForm").submit();
                }
            }
        });

        //日期
        laydate.render({
            elem: '#startTime'
            ,type: 'datetime'
        });

        //日期
        laydate.render({
            elem: '#endTime'
            ,type: 'datetime'
        });

        //查询
        $(".search_btn").click(function(){
            $("#searchForm").submit();
        });
    })
</script>
</body>
</html>
