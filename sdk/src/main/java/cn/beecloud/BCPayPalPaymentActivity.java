/**
 * BCPayPalPaymentActivity.java
 *
 * Created by xuanzhui on 2015/8/31.
 * Copyright (c) 2015 BeeCloud. All rights reserved.
 */
package cn.beecloud;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.google.gson.Gson;
import com.paypal.android.sdk.payments.PayPalConfiguration;
import com.paypal.android.sdk.payments.PayPalPayment;
import com.paypal.android.sdk.payments.PayPalService;
import com.paypal.android.sdk.payments.PaymentActivity;
import com.paypal.android.sdk.payments.PaymentConfirmation;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import cn.beecloud.entity.BCPayResult;

/**
 * used for PayPal payment
 */
public class BCPayPalPaymentActivity extends Activity {

    private final static String TAG = "BCPayPalPaymentActivity";
    private Integer billTotalFee;
    private String billTitle;
    private String currency;
    private String optional;

    private static PayPalConfiguration config = new PayPalConfiguration()
            // Start with mock environment.  When ready, switch to sandbox (ENVIRONMENT_SANDBOX)
            // or live (ENVIRONMENT_PRODUCTION)
            .environment(BCCache.getInstance(null).paypalPayType == BCPay.PAYPAL_PAY_TYPE.LIVE ?
                    PayPalConfiguration.ENVIRONMENT_PRODUCTION : PayPalConfiguration.ENVIRONMENT_SANDBOX)
            .clientId(BCCache.getInstance(null).paypalClientID)
            .acceptCreditCards(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = new Intent(this, PayPalService.class);

        intent.putExtra(PayPalService.EXTRA_PAYPAL_CONFIGURATION, config);

        startService(intent);
    }

    @Override
    public void onStart() {
        super.onStart();

        billTotalFee = getIntent().getIntExtra("billTotalFee", 0);
        billTitle = getIntent().getStringExtra("billTitle");
        currency = getIntent().getStringExtra("currency");
        optional = getIntent().getStringExtra("optional");

        PayPalPayment payment = new PayPalPayment(new BigDecimal(billTotalFee/100.0),
                currency, billTitle, PayPalPayment.PAYMENT_INTENT_SALE);

        if (BCCache.getInstance(null).retrieveShippingAddresses != null)
            payment.enablePayPalShippingAddressesRetrieval(BCCache.getInstance(null).retrieveShippingAddresses);

        Intent intent = new Intent(this, PaymentActivity.class);

        // send the same configuration for restart resiliency
        intent.putExtra(PayPalService.EXTRA_PAYPAL_CONFIGURATION, config);

        intent.putExtra(PaymentActivity.EXTRA_PAYMENT, payment);

        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {

        if (BCPay.payCallback == null) {
            Log.e(TAG, "BCPay payCallback NPE");
            this.finish();
        }

        if (resultCode == Activity.RESULT_OK) {
            PaymentConfirmation confirm = data.getParcelableExtra(PaymentActivity.EXTRA_RESULT_CONFIRMATION);
            if (confirm != null && confirm.getProofOfPayment() != null &&
                    confirm.getProofOfPayment().getPaymentId() != null &&
                    confirm.getProofOfPayment().getPaymentId().length() > 4) {

                final String billNum = confirm.getProofOfPayment().getPaymentId().substring(4);

                //Log.w("BCPayPalPaymentActivity", billNum);

                if (confirm.getProofOfPayment().getState() != null &&
                        confirm.getProofOfPayment().getState().equals("approved")){
                    BCPay.payCallback.done(new BCPayResult(BCPayResult.RESULT_SUCCESS,
                            BCPayResult.RESULT_SUCCESS, BCPayResult.RESULT_SUCCESS));

                    BCCache.executorService.execute(new Runnable() {
                        @Override
                        public void run() {

                            Log.i(TAG, "sync with server...");

                            String remoteRes = BCPay.getInstance(BCPayPalPaymentActivity.this).syncPayPalPayment(
                                    billTitle, billTotalFee, billNum, currency,
                                    optional, BCCache.getInstance(null).paypalPayType, null);

                            //Log.w(TAG, remoteRes);

                            //verify fail, keep record to SharedPreferences
                            if (!remoteRes.equals(BCPayResult.RESULT_SUCCESS)) {
                                Map<String, String> payInfo = new HashMap<String, String>();
                                payInfo.put("billTitle", billTitle);
                                payInfo.put("billTotalFee", String.valueOf(billTotalFee));
                                payInfo.put("optional", optional);
                                payInfo.put("billNum", billNum);
                                payInfo.put("channel", String.valueOf(BCCache.getInstance(null).paypalPayType));
                                payInfo.put("storeDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                                payInfo.put("currency", currency);

                                Gson gson = new Gson();
                                String paystr = gson.toJson(payInfo);
                                Log.w(TAG, "store un-synced bill...");
                                BCCache.getInstance(BCPayPalPaymentActivity.this).storeUnSyncedPayPalRecords(paystr);
                            }
                        }
                    });

                } else {
                    BCPay.payCallback.done(new BCPayResult(BCPayResult.RESULT_FAIL,
                            BCPayResult.FAIL_ERR_FROM_CHANNEL, "not approved by PayPal Android SDK"));
                }
            } else {
                BCPay.payCallback.done(new BCPayResult(BCPayResult.RESULT_FAIL,
                        BCPayResult.FAIL_ERR_FROM_CHANNEL, "no confirm from PayPal Android SDK"));
            }
        }
        else if (resultCode == Activity.RESULT_CANCELED) {
            BCPay.payCallback.done(new BCPayResult(BCPayResult.RESULT_CANCEL,
                    BCPayResult.RESULT_CANCEL, BCPayResult.RESULT_CANCEL));
        }
        else if (resultCode == PaymentActivity.RESULT_EXTRAS_INVALID) {
            BCPay.payCallback.done(new BCPayResult(BCPayResult.RESULT_FAIL,
                    BCPayResult.FAIL_ERR_FROM_CHANNEL,
                    "An invalid Payment or PayPalConfiguration was submitted."));
        }

        finish();
    }



    @Override
    public void onDestroy() {
        stopService(new Intent(this, PayPalService.class));
        super.onDestroy();
    }
}