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
import com.google.android.gms.common.api.Status;
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
import com.stripe.android.Stripe;
import com.stripe.android.TokenCallback;
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

import java.util.ArrayList;

public class StripeModule extends ReactContextBaseJavaModule {

  private static final String TAG = "### StripeModule: ";
  private static final String MODULE_NAME = "StripeModule";

  private static final int LOAD_MASKED_WALLET_REQUEST_CODE = 100502;
  private static final int LOAD_FULL_WALLET_REQUEST_CODE = 100503;

  private static final String PURCHASE_CANCELLED = "PURCHASE_CANCELLED";

  private static final boolean IS_LOGGING_ENABLED = true;

  //mAndroidPayParams keys:
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

  private static StripeModule sInstance = null;

  public static StripeModule getInstance() {
    return sInstance;
  }

  public Stripe getStripe() {
    return mStripe;
  }

  @Nullable
  private Promise mCreateSourcePromise;
  private Promise mPayPromise;

  @Nullable
  private Source mCreatedSource;

  private String mPublicKey;
  private Stripe mStripe;
  private GoogleApiClient mGoogleApiClient;

  private ReadableMap mAndroidPayParams;

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      if (mPayPromise != null) {
        if (requestCode == LOAD_MASKED_WALLET_REQUEST_CODE) { // Unique, identifying constant
          handleLoadMascedWaletRequest(resultCode, data);
        } else if (requestCode == LOAD_FULL_WALLET_REQUEST_CODE) {
          if (resultCode == Activity.RESULT_OK) {
            FullWallet fullWallet = data.getParcelableExtra(WalletConstants.EXTRA_FULL_WALLET);
            String tokenJSON = fullWallet.getPaymentMethodToken().getToken();
            Token token = Token.fromString(tokenJSON);
            if (token == null) {
              // Log the error and notify Stripe help
              mPayPromise.reject("JsonParsingError", "Failed to create token from JSON string.");
            } else {
              mPayPromise.resolve(convertTokenToWritableMap(token));
            }
          } else if(resultCode == Activity.RESULT_CANCELED) {
          } else {
          }
        } else {
          super.onActivityResult(activity, requestCode, resultCode, data);
        }
      } else {
      }
    }
  };


  public StripeModule(ReactApplicationContext reactContext) {
    super(reactContext);

    // Add the listener for `onActivityResult`
    reactContext.addActivityEventListener(mActivityEventListener);

    sInstance = this;
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  @ReactMethod
  public void init(ReadableMap options) {
    if(exist(options, ANDROID_PAY_MODE, PRODUCTION).toLowerCase().equals("test")) {
      mEnvironment = WalletConstants.ENVIRONMENT_TEST;
    }
    mPublicKey = options.getString("publishableKey");
    mStripe = new Stripe(getReactApplicationContext(), mPublicKey);
  }

  @ReactMethod
  public void deviceSupportsAndroidPay(final Promise promise) {
    if (!isPlayServicesAvailable()) {
      promise.reject(TAG, "Play services are not available!");
      return;
    }

    connectIfNeededAndCheckIsWalletReadyToPay(promise);
  }

  private static void rejectPromiseWithNullActivityError(final Promise promise) {
    promise.reject(TAG, "Cannot start process with no current activity");
  }

  private void connectIfNeededAndCheckIsWalletReadyToPay(final Promise promise) {
    Activity activity = getCurrentActivity();
    if(activity == null) {
      rejectPromiseWithNullActivityError(promise);
      return;
    }

    if(mGoogleApiClient == null) {
      mGoogleApiClient = buildGoogleApiClientWithConnectedCallback(
              getCurrentActivity(),
              promise,
              mEnvironment,
              new Action<Bundle>() { public void call(Bundle bundle) {
                isWalletReadyToPay(mGoogleApiClient, promise);
              }});
      mGoogleApiClient.connect();
    } else if(mGoogleApiClient.isConnected()) {
      isWalletReadyToPay(mGoogleApiClient, promise);
    } else if(!mGoogleApiClient.isConnected()) {
      mGoogleApiClient.registerConnectionCallbacks(
        createConnectionCallbacks(promise, new Action<Bundle>() { public void call(Bundle bundle) {
          isWalletReadyToPay(mGoogleApiClient, promise);
        }})
      );
      mGoogleApiClient.connect();
    } else {
      promise.reject(TAG, "Unknown error");
    }
  }

  private void connectIfNeededAndCheckIsWalletReadyToPayAndLoadMaskedWallet(final ReadableMap map) {
    final Activity activity = getCurrentActivity();
    if(activity == null) {
      rejectPromiseWithNullActivityError(mPayPromise);
      return;
    }

    if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
      checkIsWalletReadyToPayAndLoadMaskedWallet(mGoogleApiClient, mPayPromise, map);
    } else {
      mGoogleApiClient = buildGoogleApiClientWithConnectedCallback(
              activity,
              mPayPromise,
              mEnvironment,
              new Action<Bundle>() { public void call(Bundle bundle) {
                checkIsWalletReadyToPayAndLoadMaskedWallet(mGoogleApiClient, mPayPromise, map);
              }});
      mGoogleApiClient.connect();
    }
  }

  @ReactMethod
  public void createTokenWithCard(final ReadableMap cardData, final Promise promise) {
    try {
      mStripe.createToken(createCard(cardData),
              mPublicKey,
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
    try {
      mStripe.createBankAccountToken(createBankAccount(accountData),
              mPublicKey,
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
    if (getCurrentActivity() != null) {
      final AddCardDialogFragment cardDialog = AddCardDialogFragment.newInstance(mPublicKey);
      cardDialog.setPromise(promise);
      cardDialog.show(getCurrentActivity().getFragmentManager(), "AddNewCard");
    }
  }

  @ReactMethod
  public void paymentRequestWithAndroidPay(final ReadableMap map, final Promise promise) {
    mPayPromise = promise;
    connectIfNeededAndCheckIsWalletReadyToPayAndLoadMaskedWallet(map);
  }

  @ReactMethod
  public void createSourceWithParams(final ReadableMap options, final Promise promise) {
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

    mStripe.createSource(sourceParams, new SourceCallback() {
      @Override
      public void onError(Exception error) {
        promise.reject(error);
      }

      @Override
      public void onSuccess(Source source) {
        if (Source.REDIRECT.equals(source.getFlow())) {
          if (getCurrentActivity() == null) {
            rejectPromiseWithNullActivityError(promise);
          } else {
            mCreateSourcePromise = promise;
            mCreatedSource = source;
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
    if (mCreatedSource == null || mCreateSourcePromise == null) {

      return;
    }

    if (redirectData == null) {

      mCreateSourcePromise.reject(TAG, "Cancelled");
      mCreatedSource = null;
      mCreateSourcePromise = null;
      return;
    }

    final String clientSecret = redirectData.getQueryParameter("client_secret");
    if (!mCreatedSource.getClientSecret().equals(clientSecret)) {
      mCreateSourcePromise.reject(TAG, "Received redirect uri but there is no source to process");
      mCreatedSource = null;
      mCreateSourcePromise = null;
      return;
    }

    final String sourceId = redirectData.getQueryParameter("source");
    if (!mCreatedSource.getId().equals(sourceId)) {
      mCreateSourcePromise.reject(TAG, "Received wrong source id in redirect uri");
      mCreatedSource = null;
      mCreateSourcePromise = null;
      return;
    }

    final Promise promise = mCreateSourcePromise;

    // Nulls those properties to avoid processing them twice
    mCreatedSource = null;
    mCreateSourcePromise = null;

    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... voids) {
        Source source = null;
        try {
          source = mStripe.retrieveSourceSynchronous(sourceId, clientSecret);
        } catch (Exception e) {

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

  private static GoogleApiClient.ConnectionCallbacks createConnectionCallbacks(final Promise promise, final Action<Bundle> onConnected) {
    return new GoogleApiClient.ConnectionCallbacks() {
      @Override
      public void onConnected(@Nullable Bundle bundle) {
        onConnected.call(bundle);
      }

      @Override
      public void onConnectionSuspended(int i) {
        promise.reject(TAG, "onConnectionSuspended i = " + i);
      }
    };
  }

  private static GoogleApiClient buildGoogleApiClientWithConnectedCallback(Activity activity, final Promise promise, int env, final Action<Bundle> onConnected) {
    GoogleApiClient googleApiClient = new GoogleApiClient.Builder(activity)
      .addConnectionCallbacks(createConnectionCallbacks(promise, onConnected))
      .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
          promise.reject(TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
        }
      })
      .addApi(Wallet.API, new Wallet.WalletOptions.Builder()
              .setEnvironment(env)
              .setTheme(WalletConstants.THEME_LIGHT)
              .build())
      .build();
    return googleApiClient;
  }

  private MaskedWalletRequest createWalletRequest(final String estimatedTotalPrice, final String currencyCode, final Boolean shippingAddressRequired, final ArrayList<CountrySpecification> countries) {
    final MaskedWalletRequest maskedWalletRequest = MaskedWalletRequest.newBuilder()

      // Request credit card tokenization with Stripe by specifying tokenization parameters:
      .setPaymentMethodTokenizationParameters(PaymentMethodTokenizationParameters.newBuilder()
        .setPaymentMethodTokenizationType(PaymentMethodTokenizationType.PAYMENT_GATEWAY)
        .addParameter("gateway", "stripe")
        .addParameter("stripe:publishableKey", mPublicKey)
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


    GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
    int result = googleAPI.isGooglePlayServicesAvailable(getCurrentActivity());
    if (result != ConnectionResult.SUCCESS) {
      return false;
    }
    return true;
  }

  private void androidPayUnavaliableDialog() {
    new AlertDialog.Builder(getCurrentActivity())
      .setMessage(R.string.gettipsi_android_pay_unavaliable)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  private void handleLoadMascedWaletRequest(int resultCode, Intent data) {
    if (resultCode == Activity.RESULT_OK) {

      MaskedWallet maskedWallet = data.getParcelableExtra(WalletConstants.EXTRA_MASKED_WALLET);

      final Cart.Builder cartBuilder = Cart.newBuilder()
        .setCurrencyCode(mAndroidPayParams.getString(CURRENCY_CODE))
        .setTotalPrice(mAndroidPayParams.getString(TOTAL_PRICE));

      final ReadableArray lineItems = mAndroidPayParams.getArray(LINE_ITEMS);
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

      Wallet.Payments.loadFullWallet(mGoogleApiClient, fullWalletRequest, LOAD_FULL_WALLET_REQUEST_CODE);
    } else {
      mPayPromise.reject(PURCHASE_CANCELLED, "Purchase was cancelled");
    }
  }

  private static void isWalletReadyToPay(final GoogleApiClient googleApiClient, final Promise promise) {
    isWalletReadyToPay(
            googleApiClient,
            new ResultCallback<BooleanResult>() { public void onResult(@NonNull BooleanResult result) {
              if(result.getStatus().isSuccess()) {
                promise.resolve(result.getValue());
              } else {
                promise.reject(TAG, result.getStatus().getStatusMessage());
              }
            }});
  }

  private static void isWalletReadyToPay(GoogleApiClient gac, ResultCallback<BooleanResult> callback) {
    Wallet.Payments.isReadyToPay(gac, IsReadyToPayRequest.newBuilder().build()).setResultCallback(callback);
  }

  private void checkIsWalletReadyToPayAndLoadMaskedWallet(final GoogleApiClient gac, final Promise promise, final ReadableMap map) {
    isWalletReadyToPay(
            gac,
            new ResultCallback<BooleanResult>() {
              @Override
              public void onResult(@NonNull BooleanResult result) {
                if (result.getStatus().isSuccess()) {
                  if (result.getValue()) {
                    mAndroidPayParams = map;
                    final String estimatedTotalPrice = map.getString(TOTAL_PRICE);
                    final String currencyCode = map.getString(CURRENCY_CODE);
                    final Boolean shippingAddressRequired = exist(map, SHIPPING_ADDRESS_REQUIRED, true);
                    final ArrayList<CountrySpecification> allowedCountries = getAllowedShippingCountries(map);
                    final MaskedWalletRequest maskedWalletRequest = createWalletRequest(estimatedTotalPrice, currencyCode, shippingAddressRequired, allowedCountries);
                    Wallet.Payments.loadMaskedWallet(gac, maskedWalletRequest, LOAD_MASKED_WALLET_REQUEST_CODE);
                  } else {
                    androidPayUnavaliableDialog();
                    promise.reject(TAG, "Android Pay unavaliable");
                  }
                } else {
                  androidPayUnavaliableDialog();
                  promise.reject(TAG, "Error making isReadyToPay call");
                }
              }
            });
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

  private interface Action<T> {
    void call(T t);
  }
}
