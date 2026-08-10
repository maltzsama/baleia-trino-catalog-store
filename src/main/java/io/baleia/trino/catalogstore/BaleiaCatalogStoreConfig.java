package io.baleia.trino.catalogstore;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.units.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import static java.util.concurrent.TimeUnit.SECONDS;

public class BaleiaCatalogStoreConfig
{
    private String jdbcUrl;
    private String username;
    private String password;
    private String clusterName = "default";
    private Duration connectTimeout = new Duration(10, SECONDS);
    private Duration socketTimeout = new Duration(30, SECONDS);
    private int maxConnectAttempts = 5;
    private Duration initialBackoff = new Duration(2, SECONDS);
    private Duration maxBackoff = new Duration(30, SECONDS);

    @NotNull
    public String getJdbcUrl()
    {
        return jdbcUrl;
    }

    @Config("baleia.jdbc-url")
    @ConfigDescription("JDBC URL of Baleia's PostgreSQL")
    public BaleiaCatalogStoreConfig setJdbcUrl(String jdbcUrl)
    {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    @NotNull
    public String getUsername()
    {
        return username;
    }

    @Config("baleia.username")
    public BaleiaCatalogStoreConfig setUsername(String username)
    {
        this.username = username;
        return this;
    }

    @NotNull
    public String getPassword()
    {
        return password;
    }

    @Config("baleia.password")
    @ConfigSecuritySensitive
    public BaleiaCatalogStoreConfig setPassword(String password)
    {
        this.password = password;
        return this;
    }

    @NotNull
    public String getClusterName()
    {
        return clusterName;
    }

    @Config("baleia.cluster-name")
    @ConfigDescription("Name in trino_clusters that this coordinator represents")
    public BaleiaCatalogStoreConfig setClusterName(String clusterName)
    {
        this.clusterName = clusterName;
        return this;
    }

    public Duration getConnectTimeout()
    {
        return connectTimeout;
    }

    @Config("baleia.connect-timeout")
    @ConfigDescription("Time to wait for a JDBC connection; e.g. \"10s\", \"1m\". Default 10s")
    public BaleiaCatalogStoreConfig setConnectTimeout(Duration connectTimeout)
    {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public Duration getSocketTimeout()
    {
        return socketTimeout;
    }

    @Config("baleia.socket-timeout")
    @ConfigDescription("Max time waiting for a socket read after connection is established; e.g. \"30s\". Default 30s")
    public BaleiaCatalogStoreConfig setSocketTimeout(Duration socketTimeout)
    {
        this.socketTimeout = socketTimeout;
        return this;
    }

    @Min(1)
    @Max(20)
    public int getMaxConnectAttempts()
    {
        return maxConnectAttempts;
    }

    @Config("baleia.max-connect-attempts")
    @ConfigDescription("Total attempts on a full connection failure, with exponential backoff. Default 5")
    public BaleiaCatalogStoreConfig setMaxConnectAttempts(int maxConnectAttempts)
    {
        this.maxConnectAttempts = maxConnectAttempts;
        return this;
    }

    public Duration getInitialBackoff()
    {
        return initialBackoff;
    }

    @Config("baleia.initial-backoff")
    @ConfigDescription("Backoff after the first failed attempt; doubles each attempt up to max-backoff. Default 2s")
    public BaleiaCatalogStoreConfig setInitialBackoff(Duration initialBackoff)
    {
        this.initialBackoff = initialBackoff;
        return this;
    }

    public Duration getMaxBackoff()
    {
        return maxBackoff;
    }

    @Config("baleia.max-backoff")
    @ConfigDescription("Upper bound on the exponential backoff between retry attempts. Default 30s")
    public BaleiaCatalogStoreConfig setMaxBackoff(Duration maxBackoff)
    {
        this.maxBackoff = maxBackoff;
        return this;
    }

    @AssertTrue(message = "timeouts and backoffs must be strictly positive, and initial-backoff must not exceed max-backoff")
    public boolean isBackoffConfigurationValid()
    {
        return connectTimeout.toMillis() > 0
                && socketTimeout.toMillis() > 0
                && initialBackoff.toMillis() > 0
                && maxBackoff.toMillis() > 0
                && initialBackoff.toMillis() <= maxBackoff.toMillis();
    }
}
