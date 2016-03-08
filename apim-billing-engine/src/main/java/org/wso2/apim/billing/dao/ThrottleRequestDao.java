/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*
*/
package org.wso2.apim.billing.dao;

import org.wso2.apim.billing.bean.AggregateField;
import org.wso2.apim.billing.bean.AppApiSubscriptionBean;
import org.wso2.apim.billing.domain.InvoiceEntity;
import org.wso2.apim.billing.bean.SearchRequestBean;
import org.wso2.apim.billing.clients.APIRESTClient;
import org.wso2.apim.billing.clients.DASRestClient;
import org.wso2.apim.billing.domain.PlanEntity;
import org.wso2.apim.billing.domain.UserEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class ThrottleRequestDao {

    public static final String DAS_AGGREGATES_SEARCH_REST_API_URL = "/analytics/aggregates";
    private String apimStoreUrl;
    private String apimUserName;
    private String apimPassword;

    private String dasUrl;
    private String dasUserName;
    private String dasPassword;
    private PlanDao planDao;
    private String jksPath;

    public ThrottleRequestDao() {

    }

    public String getJksPath() {
        return jksPath;
    }

    public void setJksPath(String jksPath) {
        this.jksPath = jksPath;
    }

    public PlanDao getPlanDao() {
        return planDao;
    }

    public void setPlanDao(PlanDao planDao) {
        this.planDao = planDao;
    }

    public static String getDasAggregatesSearchRestApiUrl() {
        return DAS_AGGREGATES_SEARCH_REST_API_URL;
    }

    public String getApimStoreUrl() {
        return apimStoreUrl;
    }

    public void setApimStoreUrl(String apimStoreUrl) {
        this.apimStoreUrl = apimStoreUrl;
    }

    public String getApimUserName() {
        return apimUserName;
    }

    public void setApimUserName(String apimUserName) {
        this.apimUserName = apimUserName;
    }

    public String getApimPassword() {
        return apimPassword;
    }

    public void setApimPassword(String apimPassword) {
        this.apimPassword = apimPassword;
    }

    public String getDasUrl() {
        return dasUrl;
    }

    public void setDasUrl(String dasUrl) {
        this.dasUrl = dasUrl;
    }

    public String getDasUserName() {
        return dasUserName;
    }

    public void setDasUserName(String dasUserName) {
        this.dasUserName = dasUserName;
    }

    public String getDasPassword() {
        return dasPassword;
    }

    public void setDasPassword(String dasPassword) {
        this.dasPassword = dasPassword;
    }

    private InvoiceEntity getInvoice(int success, int throttle, String planName, UserEntity user) {

        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Calendar calendar = Calendar.getInstance();
        String billDate = dateFormat.format(calendar.getTime());
        calendar.set(Calendar.MONTH, calendar.get(Calendar.MONTH) + 1);
        String dueDate = dateFormat.format(calendar.getTime());

        PlanEntity plan = planDao.loadPlanByPlanName(planName);

        throttle = getThrottleCount(plan, success, throttle);

        double subscriptionFee = plan.getSubscriptionFee();
        double successFee = getSuccessRequestFee(plan, success);
        double throttleFee = getThrottleRequestFee(plan, throttle);
        double totalFee = subscriptionFee + successFee + throttleFee;

        double feePerRequest = getPerSuccessFee(plan);
        double feePerThrottle = getPerThrottleFee(plan);

        int ran = (int) (Math.random() * 1000);

        InvoiceEntity invoiceEntity = new InvoiceEntity();
        invoiceEntity.setAddress1(user.getAddress1());
        invoiceEntity.setAddress2(user.getAddress2());
        invoiceEntity.setAddress3(user.getAddress3());
        invoiceEntity.setCreatedDate(billDate);
        invoiceEntity.setDueDate(dueDate);
        invoiceEntity.setInvoiceNo(ran);
        invoiceEntity.setPaymentMethod(user.getCardType());
        invoiceEntity.setSubscriptionFee(plan.getSubscriptionFee());
        invoiceEntity.setSuccessCount(success);
        invoiceEntity.setSuccessFee(successFee);
        invoiceEntity.setThrottleCount(throttle);
        invoiceEntity.setThrottleFee(throttleFee);
        invoiceEntity.setTotalFee(totalFee);
        invoiceEntity.setUserCompany(user.getCompany());
        invoiceEntity.setUserEmail(user.getEmail());
        invoiceEntity.setUserFirstName(user.getFirstName());
        invoiceEntity.setUserLastName(user.getLastName());
        invoiceEntity.setPlanName(plan.getPlanName());
        invoiceEntity.setFeePerSuccess(feePerRequest);
        invoiceEntity.setFeePerThrottle(feePerThrottle);
        invoiceEntity.setPlanType(plan.getPlanType());
        return invoiceEntity;
    }

    public InvoiceEntity GenerateInvoice(String planName, UserEntity user) {

        System.setProperty("javax.net.ssl.trustStore", jksPath);
        System.setProperty("javax.net.ssl.trustStorePassword", "wso2carbon");

        if (planName == null) {
            planName = "silver";
        }

        try {
            DASRestClient s = new DASRestClient(this.dasUrl, this.dasUserName, this.dasPassword.toCharArray());

            String query = getQuery(planName);//"tenantDomain" + ":\"" + "admin@carbon.super" + "\"";
            if (query == null) {
                System.out.println("no subscription for plan: " + planName);
                return null;
            }

            //creating request bean
            SearchRequestBean request = new SearchRequestBean(query, 1, "tenantDomain_userId_facet",
                    "THROTTLED_SUMMARY");
            ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
            AggregateField field = new AggregateField("success_request_count", "sum", "sCount");
            AggregateField field2 = new AggregateField("throttleout_count", "sum", "tCount");
            fields.add(field);
            fields.add(field2);
            request.setAggregateFields(fields);

            CloseableHttpResponse res = s.doPost(request, this.dasUrl + DAS_AGGREGATES_SEARCH_REST_API_URL);
            String resMsg = getResponseBody(res);
            System.out.println("response: " + resMsg);
            JSONArray obj = new JSONArray(resMsg);
            JSONObject val = obj.getJSONObject(0).getJSONObject("values");
            int sCount = val.getInt("sCount");
            int tCount = val.getInt("tCount");
            InvoiceEntity result = getInvoice(sCount, tCount, planName, user);

            return result;

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private String getResponseBody(HttpResponse response) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), "UTF-8"));
        String line;
        StringBuffer sb = new StringBuffer();

        while ((line = rd.readLine()) != null) {
            sb.append(line);
        }
        rd.close();
        return sb.toString();
    }

    private String getQuery(String planName) throws Exception {
        APIRESTClient r = new APIRESTClient(this.apimStoreUrl);
        r.login(this.apimUserName, this.apimPassword);
        List<AppApiSubscriptionBean> beans = r.listSubscriptionBeans(planName);

        if (beans == null || beans.isEmpty()) {
            return null;
        }

        StringBuilder query = new StringBuilder();
        AppApiSubscriptionBean bean1 = beans.get(0);
        query.append("(").append("api:\"").append(bean1.getApiName()).append("\" AND ").append("applicationName:\"")
                .append(bean1.getAppName()).append("\" AND ").append("version:\"").append(bean1.getApiName())
                .append(":v").append(bean1.getVersion()).append("\" ) ");

        for (int i = 1; i < beans.size(); i++) {
            AppApiSubscriptionBean bean = beans.get(i);
            query.append("OR (").append("api:\"").append(bean.getApiName()).append("\" AND ")
                    .append("applicationName:\"").append(bean.getAppName()).append("\" AND ").append("version:\"")
                    .append(bean.getApiName()).append(":v").append(bean.getVersion()).append("\" ) ");
        }

        return query.toString();
    }

    private double getSuccessRequestFee(PlanEntity plan, int success) {
        if (plan.getPlanType().equals("STANDARD")) {
            return 0.0;
        } else if (plan.getPlanType().equals("USAGE")) {
            return success * plan.getFeePerRequest();
        } else {
            return 0.0;
        }
    }

    private double getThrottleRequestFee(PlanEntity plan, int throttle) {
        if (plan.getPlanType().equals("STANDARD")) {
            return throttle * plan.getFeePerRequest();
        } else if (plan.getPlanType().equals("USAGE")) {
            return 0.0;
        } else {
            return 0.0;
        }
    }

    private int getThrottleCount(PlanEntity plan, int success, int throttle) {
        if (plan.getPlanType().equals("STANDARD")) {
            int diff = success - Integer.parseInt(plan.getQuota());
            if (diff > 0) {
                return diff;
            } else {
                return success;
            }
        } else if (plan.getPlanType().equals("USAGE")) {
            return throttle;
        } else {
            return throttle;
        }
    }

    private double getPerSuccessFee(PlanEntity plan) {
        if (plan.getPlanType().equals("STANDARD")) {
            return 0.0;
        } else {
            return plan.getFeePerRequest();
        }
    }

    private double getPerThrottleFee(PlanEntity plan) {
        if (plan.getPlanType().equals("STANDARD")) {
            return plan.getFeePerRequest();
        } else {
            return 0.0;
        }
    }
}
