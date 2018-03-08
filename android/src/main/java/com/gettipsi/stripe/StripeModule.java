package com.gettipsi.stripe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.text.TextUtils;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.gettipsi.stripe.dialog.AddCardDialogFragment;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.BooleanResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wallet.Cart;
import com.google.android.gms.wallet.FullWallet;
import com.google.android.gms.wallet.FullWalletRequest;
import com.google.android.gms.wallet.IsReadyToPayRequest;
import com.google.android.gms.wallet.LineItem;
import com.google.android.gms.wallet.MaskedWallet;
import com.google.android.gms.wallet.MaskedWalletRequest;
import com.google.android.gms.wallet.PaymentMethodTokenizationParameters;
import com.google.android.gms.wallet.PaymentMethodTokenizationType;
import com.google.android.gms.wallet.Wallet;
import com.google.android.gms.wallet.WalletConstants;
import com.google.android.gms.identity.intents.model.UserAddress;
import com.google.android.gms.identity.intents.model.CountrySpecification;
import com.stripe.android.BuildConfig;
import com.stripe.android.SourceCallback;
import com.google.android.gms.identity.intents.model.CountrySpecification;
import com.stripe.android.Stripe;
import com.stripe.android.TokenCallback;
import com.stripe.android.exception.APIConnectionException;
import com.stripe.android.exception.APIException;
import com.stripe.android.exception.AuthenticationException;
import com.stripe.android.exception.CardException;
import com.stripe.android.exception.InvalidRequestException;
import com.stripe.android.model.Address;
import com.stripe.android.model.BankAccount;
import com.stripe.android.model.Card;
import com.stripe.android.model.Source;
import com.stripe.android.model.SourceCodeVerification;
import com.stripe.android.model.SourceOwner;
import com.stripe.android.model.SourceParams;
import com.stripe.android.model.SourceReceiver;
import com.stripe.android.model.SourceRedirect;
import com.stripe.android.model.Token;

import java.util.Map;

import java.util.List;
import java.util.ArrayList;

public class StripeModule extends ReactContextBaseJavaModule {

  private static final String TAG = "### StripeModule: ";
  private static final String MODULE_NAME = "StripeModule";

  private static final int LOAD_MASKED_WALLET_REQUEST_CODE = 100502;
  private static final int LOAD_FULL_WALLET_REQUEST_CODE = 100503;

  private static final String PURCHASE_CANCELLED = "PURCHASE_CANCELLED";

  private static final boolean IS_LOGGING_ENABLED = true;

  //androidPayParams keys:
  private static final String ANDROID_PAY_MODE = "androidPayMode";
  private static final String PRODUCTION = "production";
  private static final String CURRENCY_CODE = "currency_code";
  private static final String SHIPPING_ADDRESS_REQUIRED = "shipping_address_required";
  private static final String TOTAL_PRICE = "total_price";
  private static final String UNIT_PRICE = "unit_price";
  private static final String LINE_ITEMS = "line_items";
  private static final String QUANTITY = "quantity";
  private static final String DESCRIPTION = "description";

  private int mEnvironment = WalletConstants.ENVIRONMENT_PRODUCTION;

  private static StripeModule instance = null;

  public static StripeModule getInstance() {
    return instance;
  }

  public Stripe getStripe() {
    return stripe;
  }

  @Nullable
  private Promise createSourcePromise;
  private Promise payPromise;

  @Nullable
  private Source createdSource;

  private String publicKey;
  private Stripe stripe;
  private GoogleApiClient googleApiClient;

  private ReadableMap androidPayParams;

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      log("(1.0) onActivityResult()");

      if (payPromise != null) {
        log("(1.1) payPromise != null");
        if (requestCode == LOAD_MASKED_WALLET_REQUEST_CODE) { // Unique, identifying constant
          log("(1.2) requestCode == LOAD_MASKED_WALLET_REQUEST_CODE");
          handleLoadMascedWaletRequest(resultCode, data);

        } else if (requestCode == LOAD_FULL_WALLET_REQUEST_CODE) {
          log("(1.3) requestCode == LOAD_FULL_WALLET_REQUEST_CODE");
          if (resultCode == Activity.RESULT_OK) {
            log("(1.4) onActivityResult: LOAD_FULL_WALLET -> RESULT_OK");
            FullWallet fullWallet = data.getParcelableExtra(WalletConstants.EXTRA_FULL_WALLET);
            String tokenJSON = fullWallet.getPaymentMethodToken().getToken();
            Token token = Token.fromString(tokenJSON);
            if (token == null) {
              // Log the error and notify Stripe help
              log("(1.5) onActivityResult: failed to create token from JSON string.");
              payPromise.reject("JsonParsingError", "Failed to create token from JSON string.");
            } else {
              log("(1.6) onActivityResult: token != null, resolving promise!");
              payPromise.resolve(convertTokenToWritableMap(token));
            }
          } else if(resultCode == Activity.RESULT_CANCELED) {
            log("(1.8) onActivityResult: resultCode == Activity.RESULT_CANCELED");
          } else {
            log("(1.9) onActivityResult: resultCode == " + resultCode);
          }
        } else {
          log("(1.7) payPromise != null || requestCode != LOAD_FULL_WALLET_REQUEST_CODE");
          super.onActivityResult(activity, requestCode, resultCode, data);
        }
      } else {
        log("(1.8) payPromise == null");
      }
    }
  };


  public StripeModule(ReactApplicationContext reactContext) {
    super(reactContext);
    log("(2.0) StripeModule()");

    // Add the listener for `onActivityResult`
    reactContext.addActivityEventListener(mActivityEventListener);

    instance = this;
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  @ReactMethod
  public void init(ReadableMap options) {
    log("(3.0) init()");

    if(exist(options, ANDROID_PAY_MODE, PRODUCTION).toLowerCase().equals("test")) {
      log("(3.1) exist(options, ANDROID_PAY_MODE, PRODUCTION).toLowerCase().equals(test)");
      mEnvironment = WalletConstants.ENVIRONMENT_TEST;
    }

    publicKey = options.getString("publishableKey");
    stripe = new Stripe(getReactApplicationContext(), publicKey);
  }

  @ReactMethod
  public void deviceSupportsAndroidPay(final Promise promise) {
    log("(4.0) deviceSupportsAndroidPay()");

    if (!isPlayServicesAvailable()) {
      log("(4.1) !isPlayServicesAvailable()");
      promise.reject(TAG, "Play services are not available!");
      return;
    }
    if (googleApiClient != null && googleApiClient.isConnected()) {
      log("(4.2) googleApiClient != null && googleApiClient.isConnected()");
      checkAndroidPayAvaliable(promise);
    } else if (googleApiClient != null && !googleApiClient.isConnected()) {
      log("(4.3) googleApiClient != null && !googleApiClient.isConnected()");
      googleApiClient.registerConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
        @Override
        public void onConnected(@Nullable Bundle bundle) {
          log("(4.4) onConnected()");
          checkAndroidPayAvaliable(promise);
        }

        @Override
        public void onConnectionSuspended(int i) {
          log("(4.5) onConnectionSuspended()");
          promise.reject(TAG, "onConnectionSuspended i = " + i);
        }
      });
      googleApiClient.connect();
    } else if (googleApiClient == null && getCurrentActivity() != null) {
      log("(4.6) googleApiClient == null && getCurrentActivity() != null");
      googleApiClient = new GoogleApiClient.Builder(getCurrentActivity())
        .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
          @Override
          public void onConnected(@Nullable Bundle bundle) {
            log("(4.7) onConnected()");
            checkAndroidPayAvaliable(promise);
          }

          @Override
          public void onConnectionSuspended(int i) {
            log("(4.8) onConnectionSuspended()");
            promise.reject(TAG, "onConnectionSuspended i = " + i);
          }
        })
        .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
          @Override
          public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            log("(4.9) onConnectionFailed()");
            promise.reject(TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
          }
        })
        .addApi(Wallet.API, new Wallet.WalletOptions.Builder()
          .setEnvironment(mEnvironment)
          .setTheme(WalletConstants.THEME_LIGHT)
          .build())
        .build();
      googleApiClient.connect();
    } else {
      log("(4.10) googleApiClient == null && getCurrentActivity() == null");
      promise.reject(TAG, "Unknown error");
    }
  }

  @ReactMethod
  public void createTokenWithCard(final ReadableMap cardData, final Promise promise) {
    log("(5.0) createTokenWithCard()");

    try {

      stripe.createToken(createCard(cardData),
        publicKey,
        new TokenCallback() {
          public void onSuccess(Token token) {
            promise.resolve(convertTokenToWritableMap(token));
          }

          public void onError(Exception error) {
            error.printStackTrace();
            promise.reject(TAG, error.getMessage());
          }
        });
    } catch (Exception e) {
      promise.reject(TAG, e.getMessage());
    }
  }

  @ReactMethod
  public void createTokenWithBankAccount(final ReadableMap accountData, final Promise promise) {
    log("(6.0) createTokenWithBankAccount()");

    try {
      stripe.createBankAccountToken(createBankAccount(accountData),
        publicKey,
        null,
        new TokenCallback() {
          public void onSuccess(Token token) {
            promise.resolve(convertTokenToWritableMap(token));
          }

          public void onError(Exception error) {
            error.printStackTrace();
            promise.reject(TAG, error.getMessage());
          }
        });
    } catch (Exception e) {
      promise.reject(TAG, e.getMessage());
    }
  }

  @ReactMethod
  public void paymentRequestWithCardForm(ReadableMap unused, final Promise promise) {
    log("(7.0) paymentRequestWithCardForm()");

    if (getCurrentActivity() != null) {
      final AddCardDialogFragment cardDialog = AddCardDialogFragment.newInstance(publicKey);
      cardDialog.setPromise(promise);
      cardDialog.show(getCurrentActivity().getFragmentManager(), "AddNewCard");
    }
  }

  @ReactMethod
  public void paymentRequestWithAndroidPay(final ReadableMap map, final Promise promise) {
    log("(8.0) paymentRequestWithAndroidPay()");

    if (getCurrentActivity() != null) {
      log("(8.1) paymentRequestWithAndroidPay(): getCurrentActivity() != null");
      payPromise = promise;
      startApiClientAndAndroidPay(getCurrentActivity(), map);
    } else {
      log("(8.2) paymentRequestWithAndroidPay(): getCurrentActivity() == null");
    }
  }

  @ReactMethod
  public void createSourceWithParams(final ReadableMap options, final Promise promise) {
    log("(9.0) createSourceWithParams()");

    String sourceType = options.getString("type");
    SourceParams sourceParams = null;
    switch (sourceType) {
      case "alipay":
        sourceParams = SourceParams.createAlipaySingleUseParams(
            options.getInt("amount"),
            options.getString("currency"),
            getStringOrNull(options, "name"),
            getStringOrNull(options, "email"),
            options.getString("returnURL"));
        break;
      case "bancontact":
        sourceParams = SourceParams.createBancontactParams(
            options.getInt("amount"),
            options.getString("name"),
            options.getString("returnURL"),
            getStringOrNull(options, "statementDescriptor"));
        break;
      case "bitcoin":
        sourceParams = SourceParams.createBitcoinParams(
            options.getInt("amount"), options.getString("currency"), options.getString("email"));
        break;
      case "giropay":
        sourceParams = SourceParams.createGiropayParams(
            options.getInt("amount"),
            options.getString("name"),
            options.getString("returnURL"),
            getStringOrNull(options, "statementDescriptor"));
        break;
      case "ideal":
        sourceParams = SourceParams.createIdealParams(
            options.getInt("amount"),
            options.getString("name"),
            options.getString("returnURL"),
            getStringOrNull(options, "statementDescriptor"),
            getStringOrNull(options, "bank"));
        break;
      case "sepaDebit":
        sourceParams = SourceParams.createSepaDebitParams(
            options.getString("name"),
            options.getString("iban"),
            getStringOrNull(options, "addressLine1"),
            options.getString("city"),
            options.getString("postalCode"),
            options.getString("country"));
        break;
      case "sofort":
        sourceParams = SourceParams.createSofortParams(
            options.getInt("amount"),
            options.getString("returnURL"),
            options.getString("country"),
            getStringOrNull(options, "statementDescriptor"));
        break;
      case "threeDSecure":
        sourceParams = SourceParams.createThreeDSecureParams(
            options.getInt("amount"),
            options.getString("currency"),
            options.getString("returnURL"),
            options.getString("card"));
        break;

    }

    stripe.createSource(sourceParams, new SourceCallback() {
      @Override
      public void onError(Exception error) {
        promise.reject(error);
      }

      @Override
      public void onSuccess(Source source) {
        if (Source.REDIRECT.equals(source.getFlow())) {
          if (getCurrentActivity() == null) {
            promise.reject(TAG, "Cannot start payment process with no current activity");
          } else {
            createSourcePromise = promise;
            createdSource = source;
            String redirectUrl = source.getRedirect().getUrl();
            Intent browserIntent = new Intent(getCurrentActivity(), OpenBrowserActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OpenBrowserActivity.EXTRA_URL, redirectUrl);
            getCurrentActivity().startActivity(browserIntent);
          }
        } else {
          promise.resolve(convertSourceToWritableMap(source));
        }
      }
    });
  }

  private String getStringOrNull(@NonNull ReadableMap map, @NonNull String key) {
    return map.hasKey(key) ? map.getString(key) : null;
  }

  void processRedirect(@Nullable Uri redirectData) {
    log("(10.0) processRedirect()");

    if (createdSource == null || createSourcePromise == null) {
      log("(10.1) Received redirect uri but there is no source to process");
      return;
    }

    if (redirectData == null) {
      log("(10.2) Received null `redirectData`");
      createSourcePromise.reject(TAG, "Cancelled");
      createdSource = null;
      createSourcePromise = null;
      return;
    }

    final String clientSecret = redirectData.getQueryParameter("client_secret");
    if (!createdSource.getClientSecret().equals(clientSecret)) {
      createSourcePromise.reject(TAG, "Received redirect uri but there is no source to process");
      createdSource = null;
      createSourcePromise = null;
      return;
    }

    final String sourceId = redirectData.getQueryParameter("source");
    if (!createdSource.getId().equals(sourceId)) {
      createSourcePromise.reject(TAG, "Received wrong source id in redirect uri");
      createdSource = null;
      createSourcePromise = null;
      return;
    }

    final Promise promise = createSourcePromise;

    // Nulls those properties to avoid processing them twice
    createdSource = null;
    createSourcePromise = null;

    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... voids) {
        Source source = null;
        try {
          source = stripe.retrieveSourceSynchronous(sourceId, clientSecret);
        } catch (Exception e) {
          log("(10.3) Failed to retrieve source");
          return null;
        }

        switch (source.getStatus()) {
          case Source.CHARGEABLE:
          case Source.CONSUMED:
            promise.resolve(convertSourceToWritableMap(source));
            break;
          case Source.CANCELED:
            promise.reject(TAG, "User cancelled source redirect");
            break;
          case Source.PENDING:
          case Source.FAILED:
          case Source.UNKNOWN:
            promise.reject(TAG, "Source redirect failed");
        }
        return null;
      }
    }.execute();
  }

  private void startApiClientAndAndroidPay(final Activity activity, final ReadableMap map) {
    log("(11.0) startApiClientAndAndroidPay()");

    if (googleApiClient != null && googleApiClient.isConnected()) {
      log("(11.1) googleApiClient != null && googleApiClient.isConnected()");
      startAndroidPay(map);
    } else {
      log("(11.2) !(googleApiClient != null && googleApiClient.isConnected())");
      googleApiClient = new GoogleApiClient.Builder(activity)
        .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
          @Override
          public void onConnected(@Nullable Bundle bundle) {
            log("(11.3) onConnected()");
            startAndroidPay(map);
          }

          @Override
          public void onConnectionSuspended(int i) {
            log("(11.4) onConnectionSuspended()");
            payPromise.reject(TAG, "onConnectionSuspended i = " + i);
          }
        })
        .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
          @Override
          public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            log("(11.5) onConnectionFailed()");
            payPromise.reject(TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
          }
        })
        .addApi(Wallet.API, new Wallet.WalletOptions.Builder()
          .setEnvironment(mEnvironment)
          .setTheme(WalletConstants.THEME_LIGHT)
          .build())
        .build();
      googleApiClient.connect();
    }
  }

  private void showAndroidPay(final ReadableMap map) {
    log("(12)  showAndroidPay()");

    androidPayParams = map;
    final String estimatedTotalPrice = map.getString(TOTAL_PRICE);
    final String currencyCode = map.getString(CURRENCY_CODE);
    final Boolean shippingAddressRequired = exist(map, SHIPPING_ADDRESS_REQUIRED, true);
    final ArrayList<CountrySpecification> allowedCountries = getAllowedShippingCountries(map);
    final MaskedWalletRequest maskedWalletRequest = createWalletRequest(estimatedTotalPrice, currencyCode, shippingAddressRequired, allowedCountries);
    Wallet.Payments.loadMaskedWallet(googleApiClient, maskedWalletRequest, LOAD_MASKED_WALLET_REQUEST_CODE);
  }

  private MaskedWalletRequest createWalletRequest(final String estimatedTotalPrice, final String currencyCode, final Boolean shippingAddressRequired, final ArrayList<CountrySpecification> countries) {
    log("(13) createWalletRequest(), publicKey:" + publicKey);

    final MaskedWalletRequest maskedWalletRequest = MaskedWalletRequest.newBuilder()

      // Request credit card tokenization with Stripe by specifying tokenization parameters:
      .setPaymentMethodTokenizationParameters(PaymentMethodTokenizationParameters.newBuilder()
        .setPaymentMethodTokenizationType(PaymentMethodTokenizationType.PAYMENT_GATEWAY)
        .addParameter("gateway", "stripe")
        .addParameter("stripe:publishableKey", publicKey)
        .addParameter("stripe:version", BuildConfig.VERSION_NAME)
        .build())
      // You want the shipping address:
      .setShippingAddressRequired(shippingAddressRequired)
      .addAllowedCountrySpecificationsForShipping(countries)
      // Price set as a decimal:
      .setEstimatedTotalPrice(estimatedTotalPrice)
      .setCurrencyCode(currencyCode)
      .build();
    return maskedWalletRequest;
  }

  private ArrayList<CountrySpecification> getAllowedShippingCountries(final ReadableMap map) {
    ArrayList<CountrySpecification> allowedCountriesForShipping = new ArrayList<>();
    ReadableArray countries = exist(map, "shipping_countries", (ReadableArray) null);

    if(countries != null){
      for (int i = 0; i < countries.size(); i++) {
        String code = countries.getString(i);
        allowedCountriesForShipping.add(new CountrySpecification(code));
      }
    }

    return allowedCountriesForShipping;
  }

  private boolean isPlayServicesAvailable() {
    log("(14) isPlayServicesAvailable()");

    GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
    int result = googleAPI.isGooglePlayServicesAvailable(getCurrentActivity());
    if (result != ConnectionResult.SUCCESS) {
      return false;
    }
    return true;
  }

  private void androidPayUnavaliableDialog() {
    log("(15) androidPayUnavaliableDialog()");

    new AlertDialog.Builder(getCurrentActivity())
      .setMessage(R.string.gettipsi_android_pay_unavaliable)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  private void handleLoadMascedWaletRequest(int resultCode, Intent data) {
    log("(16) handleLoadMascedWaletRequest()");

    if (resultCode == Activity.RESULT_OK) {
      log("(16.1) resultCode == Activity.RESULT_OK");
      MaskedWallet maskedWallet = data.getParcelableExtra(WalletConstants.EXTRA_MASKED_WALLET);

      final Cart.Builder cartBuilder = Cart.newBuilder()
        .setCurrencyCode(androidPayParams.getString(CURRENCY_CODE))
        .setTotalPrice(androidPayParams.getString(TOTAL_PRICE));

      final ReadableArray lineItems = androidPayParams.getArray(LINE_ITEMS);
      if (lineItems != null) {
        for (int i = 0; i < lineItems.size(); i++) {
          final ReadableMap lineItem = lineItems.getMap(i);
          cartBuilder.addLineItem(LineItem.newBuilder() // Identify item being purchased
            .setCurrencyCode(lineItem.getString(CURRENCY_CODE))
            .setQuantity(lineItem.getString(QUANTITY))
            .setDescription(DESCRIPTION)
            .setTotalPrice(TOTAL_PRICE)
            .setUnitPrice(UNIT_PRICE)
            .build());
        }
      }

      final FullWalletRequest fullWalletRequest = FullWalletRequest.newBuilder()
        .setCart(cartBuilder.build())
        .setGoogleTransactionId(maskedWallet.getGoogleTransactionId())
        .build();

      Wallet.Payments.loadFullWallet(googleApiClient, fullWalletRequest, LOAD_FULL_WALLET_REQUEST_CODE);
    } else {
      log("(16.2) resultCode != Activity.RESULT_OK");
      payPromise.reject(PURCHASE_CANCELLED, "Purchase was cancelled");
    }
  }

  private IsReadyToPayRequest doIsReadyToPayRequest() {
    log("(17) doIsReadyToPayRequest()");

    return IsReadyToPayRequest.newBuilder().build();
  }

  private void checkAndroidPayAvaliable(final Promise promise) {
    log("(18) checkAndroidPayAvaliable");

    Wallet.Payments.isReadyToPay(googleApiClient, doIsReadyToPayRequest()).setResultCallback(
      new ResultCallback<BooleanResult>() {
        @Override
        public void onResult(@NonNull BooleanResult booleanResult) {
          log("(18.1) Wallet.Payments.isReadyToPay: onResult()");
          if (booleanResult.getStatus().isSuccess()) {
            log("(18.2) booleanResult.getStatus().isSuccess()");

            promise.resolve(booleanResult.getValue());
          } else {
            log("(18.3) !(booleanResult.getStatus().isSuccess())");
            // Error making isReadyToPay call
            promise.reject(TAG, booleanResult.getStatus().getStatusMessage());
          }
        }
      });
    googleApiClient = null;
    log("(18.3) googleApiClient = null ");
  }

  private void startAndroidPay(final ReadableMap map) {
    log("(19) startAndroidPay()");

    Wallet.Payments.isReadyToPay(googleApiClient, doIsReadyToPayRequest()).setResultCallback(
      new ResultCallback<BooleanResult>() {
        @Override
        public void onResult(@NonNull BooleanResult booleanResult) {
          log("(19.1) Wallet.Payments.isReadyToPay: onResult()");
          if (booleanResult.getStatus().isSuccess()) {
            log("(19.2) booleanResult.getStatus().isSuccess()");
            if (booleanResult.getValue()) {
              log("(19.3) booleanResult.getValue()");
              // TODO Work only in few countries. I don't now how test it in our countries.
              showAndroidPay(map);
            } else {
              log("(19.4) !(booleanResult.getStatus().isSuccess())");
              // Hide Android Pay buttons, show a message that Android Pay
              // cannot be used yet, and display a traditional checkout button
              androidPayUnavaliableDialog();
              payPromise.reject(TAG, "Android Pay unavaliable");
            }
          } else {
            // Error making isReadyToPay call
            log("(19.5) !(booleanResult.getStatus().isSuccess())");
            androidPayUnavaliableDialog();
            payPromise.reject(TAG, "Error making isReadyToPay call");
          }
        }
      }
    );
  }

  private Card createCard(final ReadableMap cardData) {
    return new Card(
      // required fields
      cardData.getString("number"),
      cardData.getInt("expMonth"),
      cardData.getInt("expYear"),
      // additional fields
      exist(cardData, "cvc"),
      exist(cardData, "name"),
      exist(cardData, "addressLine1"),
      exist(cardData, "addressLine2"),
      exist(cardData, "addressCity"),
      exist(cardData, "addressState"),
      exist(cardData, "addressZip"),
      exist(cardData, "addressCountry"),
      exist(cardData, "brand"),
      exist(cardData, "last4"),
      exist(cardData, "fingerprint"),
      exist(cardData, "funding"),
      exist(cardData, "country"),
      exist(cardData, "currency"),
      exist(cardData, "id")
    );
  }

  private WritableMap convertTokenToWritableMap(Token token) {
    WritableMap newToken = Arguments.createMap();

    if (token == null) return newToken;

    newToken.putString("tokenId", token.getId());
    newToken.putBoolean("livemode", token.getLivemode());
    newToken.putBoolean("used", token.getUsed());
    newToken.putDouble("created", token.getCreated().getTime());

    if (token.getCard() != null) {
      newToken.putMap("card", convertCardToWritableMap(token.getCard()));
    }
    if (token.getBankAccount() != null) {
      newToken.putMap("bankAccount", convertBankAccountToWritableMap(token.getBankAccount()));
    }

    return newToken;
  }

  @NonNull
  private WritableMap convertSourceToWritableMap(@Nullable Source source) {
    WritableMap newSource = Arguments.createMap();

    if (source == null) {
      return newSource;
    }

    newSource.putString("sourceId", source.getId());
    newSource.putInt("amount", source.getAmount().intValue());
    newSource.putInt("created", source.getCreated().intValue());
    newSource.putMap("codeVerification", convertCodeVerificationToWritableMap(source.getCodeVerification()));
    newSource.putString("currency", source.getCurrency());
    newSource.putString("flow", source.getFlow());
    newSource.putBoolean("livemode", source.isLiveMode());
    newSource.putMap("metadata", stringMapToWritableMap(source.getMetaData()));
    newSource.putMap("owner", convertOwnerToWritableMap(source.getOwner()));
    newSource.putMap("receiver", convertReceiverToWritableMap(source.getReceiver()));
    newSource.putMap("redirect", convertRedirectToWritableMap(source.getRedirect()));
    newSource.putMap("sourceTypeData", mapToWritableMap(source.getSourceTypeData()));
    newSource.putString("status", source.getStatus());
    newSource.putString("type", source.getType());
    newSource.putString("typeRaw", source.getTypeRaw());
    newSource.putString("usage", source.getUsage());

    return newSource;
  }

  @NonNull
  private WritableMap stringMapToWritableMap(@Nullable Map<String, String> map) {
    WritableMap writableMap = Arguments.createMap();

    if (map == null) {
      return writableMap;
    }

    for (Map.Entry<String, String> entry : map.entrySet()) {
      writableMap.putString(entry.getKey(), entry.getValue());
    }

    return writableMap;
  }

  @NonNull
  private WritableMap convertOwnerToWritableMap(@Nullable final SourceOwner owner) {
    WritableMap map = Arguments.createMap();

    if (owner == null) {
      return map;
    }

    map.putMap("address", convertAddressToWritableMap(owner.getAddress()));
    map.putString("email", owner.getEmail());
    map.putString("name", owner.getName());
    map.putString("phone", owner.getPhone());
    map.putString("verifiedEmail", owner.getVerifiedEmail());
    map.putString("verifiedPhone", owner.getVerifiedPhone());
    map.putString("verifiedName", owner.getVerifiedName());
    map.putMap("verifiedAddress", convertAddressToWritableMap(owner.getVerifiedAddress()));

    return map;
  }

  @NonNull
  private WritableMap convertAddressToWritableMap(@Nullable final Address address) {
    WritableMap map = Arguments.createMap();

    if (address == null) {
      return map;
    }

    map.putString("city", address.getCity());
    map.putString("country", address.getCountry());
    map.putString("line1", address.getLine1());
    map.putString("line2", address.getLine2());
    map.putString("postalCode", address.getPostalCode());
    map.putString("state", address.getState());

    return map;
  }

  @NonNull
  private WritableMap convertReceiverToWritableMap(@Nullable final SourceReceiver receiver) {
    WritableMap map = Arguments.createMap();

    if (receiver == null) {
      return map;
    }

    map.putInt("amountCharged", (int) receiver.getAmountCharged());
    map.putInt("amountReceived", (int) receiver.getAmountReceived());
    map.putInt("amountReturned", (int) receiver.getAmountReturned());
    map.putString("address", receiver.getAddress());

    return map;
  }

  @NonNull
  private WritableMap convertRedirectToWritableMap(@Nullable SourceRedirect redirect) {
    WritableMap map = Arguments.createMap();

    if (redirect == null) {
      return map;
    }

    map.putString("returnUrl", redirect.getReturnUrl());
    map.putString("status", redirect.getStatus());
    map.putString("url", redirect.getUrl());

    return map;
  }

  @NonNull
  private WritableMap convertCodeVerificationToWritableMap(@Nullable SourceCodeVerification codeVerification) {
    WritableMap map = Arguments.createMap();

    if (codeVerification == null) {
      return map;
    }

    map.putInt("attemptsRemaining", codeVerification.getAttemptsRemaining());
    map.putString("status", codeVerification.getStatus());

    return map;
  }

  @NonNull
  private WritableMap mapToWritableMap(@Nullable Map<String, Object> map){
    WritableMap writableMap = Arguments.createMap();

    if (map == null) {
      return writableMap;
    }

    for (String key: map.keySet()) {
      pushRightTypeToMap(writableMap, key, map.get(key));
    }

    return writableMap;
  }

  private void pushRightTypeToMap(@NonNull WritableMap map, @NonNull String key, @NonNull Object object) {
    Class argumentClass = object.getClass();
    if (argumentClass == Boolean.class) {
      map.putBoolean(key, (Boolean) object);
    } else if (argumentClass == Integer.class) {
      map.putDouble(key, ((Integer)object).doubleValue());
    } else if (argumentClass == Double.class) {
      map.putDouble(key, (Double) object);
    } else if (argumentClass == Float.class) {
      map.putDouble(key, ((Float)object).doubleValue());
    } else if (argumentClass == String.class) {
      map.putString(key, object.toString());
    } else if (argumentClass == WritableNativeMap.class) {
      map.putMap(key, (WritableNativeMap)object);
    } else if (argumentClass == WritableNativeArray.class) {
      map.putArray(key, (WritableNativeArray) object);
    } else {
      log("Can't map "+ key + "value of " + argumentClass.getSimpleName() + " to any valid js type,");
    }
  }

  private WritableMap convertCardToWritableMap(final Card card) {
    WritableMap result = Arguments.createMap();

    if(card == null) return result;

    result.putString("cardId", card.getId());
    result.putString("number", card.getNumber());
    result.putString("cvc", card.getCVC() );
    result.putInt("expMonth", card.getExpMonth() );
    result.putInt("expYear", card.getExpYear() );
    result.putString("name", card.getName() );
    result.putString("addressLine1", card.getAddressLine1() );
    result.putString("addressLine2", card.getAddressLine2() );
    result.putString("addressCity", card.getAddressCity() );
    result.putString("addressState", card.getAddressState() );
    result.putString("addressZip", card.getAddressZip() );
    result.putString("addressCountry", card.getAddressCountry() );
    result.putString("last4", card.getLast4() );
    result.putString("brand", card.getBrand() );
    result.putString("funding", card.getFunding() );
    result.putString("fingerprint", card.getFingerprint() );
    result.putString("country", card.getCountry() );
    result.putString("currency", card.getCurrency() );

    return result;
  }

  private WritableMap convertBankAccountToWritableMap(BankAccount account) {
    WritableMap result = Arguments.createMap();

    if(account == null) return result;

    result.putString("routingNumber", account.getRoutingNumber());
    result.putString("accountNumber", account.getAccountNumber());
    result.putString("countryCode", account.getCountryCode());
    result.putString("currency", account.getCurrency());
    result.putString("accountHolderName", account.getAccountHolderName());
    result.putString("accountHolderType", account.getAccountHolderType());
    result.putString("fingerprint", account.getFingerprint());
    result.putString("bankName", account.getBankName());
    result.putString("last4", account.getLast4());

    return result;
  }

  private WritableMap convertAddressToWritableMap(final UserAddress address){
    WritableMap result = Arguments.createMap();

    if(address == null) return result;

    putIfExist(result, "address1", address.getAddress1());
    putIfExist(result, "address2", address.getAddress2());
    putIfExist(result, "address3", address.getAddress3());
    putIfExist(result, "address4", address.getAddress4());
    putIfExist(result, "address5", address.getAddress5());
    putIfExist(result, "administrativeArea", address.getAdministrativeArea());
    putIfExist(result, "companyName", address.getCompanyName());
    putIfExist(result, "countryCode", address.getCountryCode());
    putIfExist(result, "locality", address.getLocality());
    putIfExist(result, "name", address.getName());
    putIfExist(result, "phoneNumber", address.getPhoneNumber());
    putIfExist(result, "postalCode", address.getPostalCode());
    putIfExist(result, "sortingCode", address.getSortingCode());

    return result;
  }

  private BankAccount createBankAccount(ReadableMap accountData) {
    BankAccount account = new BankAccount(
      // required fields only
      accountData.getString("accountNumber"),
      accountData.getString("countryCode"),
      accountData.getString("currency"),
      exist(accountData, "routingNumber", "")
    );
    account.setAccountHolderName(exist(accountData, "accountHolderName"));
    account.setAccountHolderType(exist(accountData, "accountHolderType"));

    return account;
  }

  private String exist(final ReadableMap map, final String key, final String def) {
    if (map.hasKey(key)) {
      return map.getString(key);
    } else {
      // If map don't have some key - we must pass to constructor default value.
      return def;
    }
  }

  private void putIfExist(final WritableMap map, final String key, final String value) {
    if (!TextUtils.isEmpty(value)) {
      map.putString(key, value);
    }
  }

  private Boolean exist(final ReadableMap map, final String key, final Boolean def) {
    if (map.hasKey(key)) {
      return map.getBoolean(key);
    } else {
      // If map don't have some key - we must pass to constructor default value.
      return def;
    }
  }

  private ReadableArray exist(final ReadableMap map, final String key, final ReadableArray def) {
    if (map.hasKey(key)) {
      return map.getArray(key);
    } else {
      // If map don't have some key - we must pass to constructor default value.
      return def;
    }
  }

  private ReadableMap exist(final ReadableMap map, final String key, final ReadableMap def) {
    if (map.hasKey(key)) {
      return map.getMap(key);
    } else {
      // If map don't have some key - we must pass to constructor default value.
      return def;
    }
  }

  private String exist(final ReadableMap map, final String key) {
    return exist(map, key, (String) null);
  }

  private static void log(String msg) {
    if(IS_LOGGING_ENABLED) {
      Log.d(TAG, msg);
    }
  }
}
