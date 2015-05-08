package org.mariotaku.twidere.util;

import android.content.Context;
import android.net.SSLCertificateSocketFactory;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Pair;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.internal.Internal;

import org.mariotaku.simplerestapi.RestAPIFactory;
import org.mariotaku.simplerestapi.RestMethod;
import org.mariotaku.simplerestapi.RestMethodInfo;
import org.mariotaku.simplerestapi.http.Authorization;
import org.mariotaku.simplerestapi.http.Endpoint;
import org.mariotaku.simplerestapi.http.RestHttpClient;
import org.mariotaku.simplerestapi.http.RestRequest;
import org.mariotaku.simplerestapi.http.RestResponse;
import org.mariotaku.twidere.TwidereConstants;
import org.mariotaku.twidere.api.twitter.Twitter;
import org.mariotaku.twidere.api.twitter.TwitterException;
import org.mariotaku.twidere.api.twitter.TwitterOAuth;
import org.mariotaku.twidere.api.twitter.api.TwitterUpload;
import org.mariotaku.twidere.api.twitter.auth.BasicAuthorization;
import org.mariotaku.twidere.api.twitter.auth.EmptyAuthorization;
import org.mariotaku.twidere.api.twitter.auth.OAuthAuthorization;
import org.mariotaku.twidere.api.twitter.auth.OAuthEndpoint;
import org.mariotaku.twidere.api.twitter.auth.OAuthToken;
import org.mariotaku.twidere.api.twitter.util.TwitterConverter;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.model.ConsumerKeyType;
import org.mariotaku.twidere.model.ParcelableAccount;
import org.mariotaku.twidere.provider.TwidereDataStore;
import org.mariotaku.twidere.util.net.OkHttpRestClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static android.text.TextUtils.isEmpty;

/**
 * Created by mariotaku on 15/5/7.
 */
public class TwitterAPIUtils implements TwidereConstants {

    public static Twitter getDefaultTwitterInstance(final Context context, final boolean includeEntities) {
        if (context == null) return null;
        return getDefaultTwitterInstance(context, includeEntities, true);
    }

    public static Twitter getDefaultTwitterInstance(final Context context, final boolean includeEntities,
                                                    final boolean includeRetweets) {
        if (context == null) return null;
        return getTwitterInstance(context, Utils.getDefaultAccountId(context), includeEntities, includeRetweets);
    }

    public static Twitter getTwitterInstance(final Context context, final long accountId,
                                             final boolean includeEntities) {
        return getTwitterInstance(context, accountId, includeEntities, true);
    }

    @Nullable
    public static Twitter getTwitterInstance(final Context context, final long accountId,
                                             final boolean includeEntities,
                                             final boolean includeRetweets) {
        return getTwitterInstance(context, accountId, includeEntities, includeRetweets, Twitter.class);
    }

    @Nullable
    public static <T> T getTwitterInstance(final Context context, final long accountId,
                                           final boolean includeEntities,
                                           final boolean includeRetweets, Class<T> cls) {
        if (context == null) return null;
        final ParcelableAccount.ParcelableCredentials credentials = ParcelableAccount.ParcelableCredentials.getCredentials(context, accountId);
        if (credentials == null) return null;
        final String apiUrlFormat;
        final boolean sameOAuthSigningUrl = credentials.same_oauth_signing_url;
        final boolean noVersionSuffix = credentials.no_version_suffix;
        final String endpointUrl, signEndpointUrl;
        if (!isEmpty(credentials.api_url_format)) {
            apiUrlFormat = credentials.api_url_format;
        } else {
            apiUrlFormat = DEFAULT_TWITTER_API_URL_FORMAT;
        }
        final String domain, versionSuffix;
        if (Twitter.class.isAssignableFrom(cls)) {
            domain = "api";
            versionSuffix = noVersionSuffix ? null : "/1.1/";
        } else if (TwitterUpload.class.isAssignableFrom(cls)) {
            domain = "upload";
            versionSuffix = noVersionSuffix ? null : "/1.1/";
        } else if (TwitterOAuth.class.isAssignableFrom(cls)) {
            domain = "api";
            versionSuffix = "oauth";
        } else {
            throw new TwitterConverter.UnsupportedTypeException(cls);
        }
        endpointUrl = Utils.getApiUrl(apiUrlFormat, domain, versionSuffix);
        if (!sameOAuthSigningUrl) {
            signEndpointUrl = Utils.getApiUrl(DEFAULT_TWITTER_API_URL_FORMAT, domain, versionSuffix);
        } else {
            signEndpointUrl = endpointUrl;
        }
        return getInstance(context, new OAuthEndpoint(endpointUrl, signEndpointUrl), credentials, cls);
    }


    public static Authorization getAuthorization(ParcelableAccount.ParcelableCredentials credentials) {
        switch (credentials.auth_type) {
            case TwidereDataStore.Accounts.AUTH_TYPE_OAUTH:
            case TwidereDataStore.Accounts.AUTH_TYPE_XAUTH: {
                final String consumerKey = TextUtils.isEmpty(credentials.consumer_key) ?
                        TWITTER_CONSUMER_KEY_LEGACY : credentials.consumer_key;
                final String consumerSecret = TextUtils.isEmpty(credentials.consumer_secret) ?
                        TWITTER_CONSUMER_SECRET_LEGACY : credentials.consumer_secret;
                final OAuthToken accessToken = new OAuthToken(credentials.oauth_token, credentials.oauth_token_secret);
                return new OAuthAuthorization(consumerKey, consumerSecret, accessToken);
            }
            case TwidereDataStore.Accounts.AUTH_TYPE_BASIC: {
                final String screenName = credentials.screen_name;
                final String username = credentials.basic_auth_username;
                final String loginName = username != null ? username : screenName;
                final String password = credentials.basic_auth_password;
                if (isEmpty(loginName) || isEmpty(password)) return null;
                return new BasicAuthorization(loginName, password);
            }
        }
        return new EmptyAuthorization();
    }


    public static <T> T getInstance(final Context context, final Endpoint endpoint, final ParcelableAccount.ParcelableCredentials credentials, Class<T> cls) {
        return getInstance(context, endpoint, getAuthorization(credentials), cls);
    }

    public static <T> T getInstance(final Context context, final Endpoint endpoint, final Authorization auth, Class<T> cls) {
        final RestAPIFactory factory = new RestAPIFactory();
        final String userAgent;
        if (auth instanceof OAuthAuthorization) {
            final String consumerKey = ((OAuthAuthorization) auth).getConsumerKey();
            final String consumerSecret = ((OAuthAuthorization) auth).getConsumerSecret();
            final ConsumerKeyType officialKeyType = TwitterContentUtils.getOfficialKeyType(context, consumerKey, consumerSecret);
            if (officialKeyType != ConsumerKeyType.UNKNOWN) {
                userAgent = Utils.getUserAgentName(officialKeyType);
            } else {
                userAgent = Utils.getTwidereUserAgent(context);
            }
        } else {
            userAgent = Utils.getTwidereUserAgent(context);
        }
        factory.setClient(getDefaultHttpClient(context));
        factory.setConverter(new TwitterConverter());
        factory.setEndpoint(endpoint);
        factory.setAuthorization(auth);
        factory.setRequestFactory(new RestRequest.Factory() {

            @Override
            public RestRequest create(@NonNull Endpoint endpoint, @NonNull RestMethodInfo info, @Nullable Authorization authorization) {
                final RestMethod restMethod = info.getMethod();
                final String url = Endpoint.constructUrl(endpoint.getUrl(), info);
                final ArrayList<Pair<String, String>> headers = new ArrayList<>(info.getHeaders());

                if (authorization != null && authorization.hasAuthorization()) {
                    headers.add(Pair.create("Authorization", authorization.getHeader(endpoint, info)));
                }
                headers.add(Pair.create("User-Agent", userAgent));
                return new RestRequest(restMethod.value(), url, headers, info.getBody(), null);
            }
        });
        factory.setExceptionFactory(new RestAPIFactory.ExceptionFactory() {
            @Override
            public Exception newException(Throwable cause, RestResponse response) {
                final TwitterException te = new TwitterException(cause);
                te.setResponse(response);
                return te;
            }
        });
        return factory.build(cls);
    }


    public static RestHttpClient getDefaultHttpClient(final Context context) {
        if (context == null) return null;
        final SharedPreferencesWrapper prefs = SharedPreferencesWrapper.getInstance(context, SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        //TODO set user agent
        TwidereApplication.getInstance(context).getDefaultUserAgent();
        return createHttpClient(context, prefs);
    }

    public static RestHttpClient createHttpClient(final Context context, final SharedPreferencesWrapper prefs) {
        final int connectionTimeout = prefs.getInt(KEY_CONNECTION_TIMEOUT, 10);
        final boolean ignoreSslError = prefs.getBoolean(KEY_IGNORE_SSL_ERROR, false);
        final boolean enableProxy = prefs.getBoolean(KEY_ENABLE_PROXY, false);

        final OkHttpClient client = new OkHttpClient();
        client.setConnectTimeout(connectionTimeout, TimeUnit.SECONDS);
        if (ignoreSslError) {
            client.setSslSocketFactory(SSLCertificateSocketFactory.getInsecure(0, null));
        } else {
            client.setSslSocketFactory(SSLCertificateSocketFactory.getDefault(0, null));
        }
        if (enableProxy) {
            client.setProxy(getProxy(prefs));
        }
        Internal.instance.setNetwork(client, TwidereApplication.getInstance(context).getNetwork());
        return new OkHttpRestClient(client);
    }


    public static Proxy getProxy(final SharedPreferencesWrapper prefs) {
        final String proxyHost = prefs.getString(KEY_PROXY_HOST, null);
        final int proxyPort = ParseUtils.parseInt(prefs.getString(KEY_PROXY_PORT, "-1"));
        if (!isEmpty(proxyHost) && proxyPort >= 0 && proxyPort < 65535) {
            final SocketAddress addr = InetSocketAddress.createUnresolved(proxyHost, proxyPort);
            return new Proxy(Proxy.Type.HTTP, addr);
        }
        return Proxy.NO_PROXY;
    }
}
