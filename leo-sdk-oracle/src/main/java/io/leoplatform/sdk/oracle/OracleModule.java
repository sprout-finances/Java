package io.leoplatform.sdk.oracle;

import dagger.Module;
import dagger.Provides;
import io.leoplatform.schema.ChangeSource;
import io.leoplatform.sdk.ExecutorManager;
import io.leoplatform.sdk.changes.AsyncChangeQueue;
import io.leoplatform.sdk.changes.ChangeReactor;
import io.leoplatform.sdk.changes.PooledChangeSource;
import io.leoplatform.sdk.changes.SchemaChangeQueue;

import javax.inject.Named;
import javax.inject.Singleton;

@Module
public final class OracleModule {
    @Singleton
    @Provides
    public static OracleChangeSource provideOracleChangeSource() {
        return new ConfigFileSource();
    }

    @Singleton
    @Provides
    public static ChangeSource provideChangeSource() {
        return new PooledChangeSource();
    }

    @Singleton
    @Provides
    public static OracleChangeRegistrar provideOracleChangeRegistrar(OracleChangeSource source, OracleChangeWriter ocw, ExecutorManager executorManager) {
        return new OracleChangeRegistrar(source, ocw, executorManager);
    }

    @Singleton
    @Provides
    public static OracleChangeLoader provideOracleChangeLoader(OracleChangeRegistrar registrar) {
        return new OracleChangeLoader(registrar);
    }

    @Singleton
    @Provides
    public static OracleChangeWriter provideOracleChangeWriter(SchemaChangeQueue changeQueue, ExecutorManager executorManager) {
        return new OracleChangeWriter(changeQueue, executorManager);
    }

    @Singleton
    @Provides
    public static SchemaChangeQueue provideSchemaChangeQueue(@Named("LeoChangeReactor") ChangeReactor changeReactor, ExecutorManager executorManager) {
        return new AsyncChangeQueue(changeReactor, executorManager);
    }
}
