package io.bisq.core.provider.price;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import io.bisq.common.UserThread;
import io.bisq.common.app.Log;
import io.bisq.common.handlers.FaultHandler;
import io.bisq.common.locale.TradeCurrency;
import io.bisq.common.util.Tuple2;
import io.bisq.core.app.BisqEnvironment;
import io.bisq.core.provider.ProvidersRepository;
import io.bisq.core.user.Preferences;
import io.bisq.network.http.HttpClient;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class PriceFeedService {
    private final HttpClient httpClient;
    private final ProvidersRepository providersRepository;
    private final Preferences preferences;

    private static final long PERIOD_SEC = 60;

    private final Map<String, MarketPrice> cache = new HashMap<>();
    private final String baseCurrencyCode;
    private PriceProvider priceProvider;
    private Consumer<Double> priceConsumer;
    private FaultHandler faultHandler;
    private String currencyCode;
    private final StringProperty currencyCodeProperty = new SimpleStringProperty();
    private final IntegerProperty currenciesUpdateFlag = new SimpleIntegerProperty(0);
    private long epochInSecondAtLastRequest;
    private Map<String, Long> timeStampMap = new HashMap<>();
    private int retryCounter = 0;
    private int retryDelay = 1;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PriceFeedService(@SuppressWarnings("SameParameterValue") HttpClient httpClient,
                            @SuppressWarnings("SameParameterValue") ProvidersRepository providersRepository,
                            @SuppressWarnings("SameParameterValue") Preferences preferences,
                            BisqEnvironment bisqEnvironment) {
        this.httpClient = httpClient;
        this.providersRepository = providersRepository;
        this.preferences = preferences;
        this.priceProvider = new PriceProvider(httpClient, providersRepository.getBaseUrl());
        baseCurrencyCode = bisqEnvironment.getBaseCurrencyNetwork().getCurrencyCode();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        if (getCurrencyCode() == null) {
            final TradeCurrency preferredTradeCurrency = preferences.getPreferredTradeCurrency();
            final String code = preferredTradeCurrency != null ? preferredTradeCurrency.getCode() : "USD";
            setCurrencyCode(code);
        }
    }

    public void requestPriceFeed(Consumer<Double> resultHandler, FaultHandler faultHandler) {
        this.priceConsumer = resultHandler;
        this.faultHandler = faultHandler;

        request();
    }

    public String getProviderNodeAddress() {
        return httpClient.getBaseUrl();
    }

    private void request() {
        requestAllPrices(priceProvider, () -> {
            applyPriceToConsumer();
            // after first response we know the providers timestamp and want to request quickly after next expected update
            long delay = Math.max(40, Math.min(90, PERIOD_SEC - (Instant.now().getEpochSecond() - epochInSecondAtLastRequest) + 2 + new Random().nextInt(5)));
            UserThread.runAfter(this::request, delay);
            retryDelay = 1;
        }, (errorMessage, throwable) -> {
            // Try other provider if more then 1 is available
            if (providersRepository.hasMoreProviders()) {
                providersRepository.setNewRandomBaseUrl();
                priceProvider = new PriceProvider(httpClient, providersRepository.getBaseUrl());
            }
            UserThread.runAfter(() -> {
                retryCounter++;
                retryDelay *= retryCounter;
                request();
            }, retryDelay);

            this.faultHandler.handleFault(errorMessage, throwable);
        });
    }

    @Nullable
    public MarketPrice getMarketPrice(String currencyCode) {
        if (cache.containsKey(currencyCode))
            return cache.get(currencyCode);
        else
            return null;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setCurrencyCode(String currencyCode) {
        if (this.currencyCode == null || !this.currencyCode.equals(currencyCode)) {
            this.currencyCode = currencyCode;
            currencyCodeProperty.set(currencyCode);
            applyPriceToConsumer();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////

    public String getCurrencyCode() {
        return currencyCode;
    }

    public StringProperty currencyCodeProperty() {
        return currencyCodeProperty;
    }

    public IntegerProperty currenciesUpdateFlagProperty() {
        return currenciesUpdateFlag;
    }

    public Date getLastRequestTimeStampBtcAverage() {
        return new Date(epochInSecondAtLastRequest * 1000);
    }

    public Date getLastRequestTimeStampPoloniex() {
        Long ts = timeStampMap.get("btcAverageTs");
        if (ts != null) {
            Date date = new Date(ts * 1000);
            return date;
        } else
            return new Date();
    }

    public Date getLastRequestTimeStampCoinmarketcap() {
        Long ts = timeStampMap.get("coinmarketcapTs");
        if (ts != null) {
            Date date = new Date(ts * 1000);
            return date;
        } else
            return new Date();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void applyPriceToConsumer() {
        if (priceConsumer != null && currencyCode != null) {
            if (cache.containsKey(currencyCode)) {
                try {
                    MarketPrice marketPrice = cache.get(currencyCode);
                    if (marketPrice.isValid())
                        priceConsumer.accept(marketPrice.getPrice());
                } catch (Throwable t) {
                    log.warn("Error at applyPriceToConsumer " + t.getMessage());
                }

            } else {
                String errorMessage = "We don't have a price for " + currencyCode;
                log.debug(errorMessage);
                faultHandler.handleFault(errorMessage, new PriceRequestException(errorMessage));
            }
        }
        currenciesUpdateFlag.setValue(currenciesUpdateFlag.get() + 1);
    }

    private void requestAllPrices(PriceProvider provider, Runnable resultHandler, FaultHandler faultHandler) {
        Log.traceCall();
        PriceRequest priceRequest = new PriceRequest();
        SettableFuture<Tuple2<Map<String, Long>, Map<String, MarketPrice>>> future = priceRequest.requestAllPrices(provider);
        Futures.addCallback(future, new FutureCallback<Tuple2<Map<String, Long>, Map<String, MarketPrice>>>() {
            @Override
            public void onSuccess(@Nullable Tuple2<Map<String, Long>, Map<String, MarketPrice>> result) {
                UserThread.execute(() -> {
                    checkNotNull(result, "Result must not be null at requestAllPrices");
                    timeStampMap = result.first;
                    epochInSecondAtLastRequest = timeStampMap.get("btcAverageTs");
                    final Map<String, MarketPrice> priceMap = result.second;
                    switch (baseCurrencyCode) {
                        case "LTC":
                            // apply conversion of btc based price to ltc based with btc/ltc price
                            MarketPrice ltcPrice = priceMap.get("LTC");
                            Map<String, MarketPrice> convertedPriceMap = new HashMap<>();
                            priceMap.entrySet().stream().forEach(e -> {
                                final MarketPrice value = e.getValue();
                                double convertedPrice = value.getPrice() * ltcPrice.getPrice();
                                convertedPriceMap.put(e.getKey(), new MarketPrice(value.getCurrencyCode(), convertedPrice, value.getTimestampSec()));
                            });
                            cache.putAll(convertedPriceMap);
                            break;
                        case "BTC":
                            // do nothing as we requrest btc based prices
                            cache.putAll(priceMap);
                            break;
                        case "DOGE":
                            // apply conversion of btc based price to doge based with btc/doge price
                            MarketPrice dogePrice = priceMap.get("DOGE");
                            convertedPriceMap = new HashMap<>();
                            priceMap.entrySet().stream().forEach(e -> {
                                final MarketPrice value = e.getValue();
                                double convertedPrice = value.getPrice() * dogePrice.getPrice();
                                convertedPriceMap.put(e.getKey(), new MarketPrice(value.getCurrencyCode(), convertedPrice, value.getTimestampSec()));
                            });
                            cache.putAll(convertedPriceMap);
                            break;
                        default:
                            throw new RuntimeException("baseCurrencyCode not dfined. baseCurrencyCode=" + baseCurrencyCode);
                    }

                    resultHandler.run();
                });
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> faultHandler.handleFault("Could not load marketPrices", throwable));
            }
        });
    }
}
