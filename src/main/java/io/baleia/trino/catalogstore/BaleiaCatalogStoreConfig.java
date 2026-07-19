package io.baleia.trino.catalogstore;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.units.Duration;
import jakarta.validation.constraints.NotNull;

import static java.util.concurrent.TimeUnit.SECONDS;

public class BaleiaCatalogStoreConfig
{
    private String jdbcUrl;
    private String username;
    private String password;
    private String clusterName = "default";
    private Duration connectTimeout = new Duration(10, SECONDS);

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
}