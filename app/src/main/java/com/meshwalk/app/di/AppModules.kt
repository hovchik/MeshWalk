package com.meshwalk.app.di

import android.content.Context
import androidx.room.Room
import com.meshwalk.app.billing.BillingManager
import com.meshwalk.app.data.local.MeshWalkDatabase
import com.meshwalk.app.data.local.dao.*
import com.meshwalk.app.data.repository.*
import com.meshwalk.app.domain.repository.*
import com.meshwalk.app.domain.usecase.CryptoManagerPort
import com.meshwalk.app.domain.usecase.MeshOutboxPort
import com.meshwalk.app.domain.usecase.TransportManagerPort
import com.meshwalk.app.crypto.keys.MeshKeyManager
import com.meshwalk.app.mesh.outbox.MeshOutbox
import com.meshwalk.app.transport.manager.TransportManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MeshWalkDatabase {
        return Room.databaseBuilder(
            context,
            MeshWalkDatabase::class.java,
            MeshWalkDatabase.DATABASE_NAME
        )
            .addMigrations(MeshWalkDatabase.MIGRATION_1_2, MeshWalkDatabase.MIGRATION_2_3, MeshWalkDatabase.MIGRATION_3_4, MeshWalkDatabase.MIGRATION_4_5, MeshWalkDatabase.MIGRATION_5_6, MeshWalkDatabase.MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideIdentityDao(db: MeshWalkDatabase): IdentityDao = db.identityDao()
    @Provides fun provideMessageDao(db: MeshWalkDatabase): MessageDao = db.messageDao()
    @Provides fun provideConversationDao(db: MeshWalkDatabase): ConversationDao = db.conversationDao()
    @Provides fun providePeerDao(db: MeshWalkDatabase): PeerDao = db.peerDao()
    @Provides fun provideRoutingDao(db: MeshWalkDatabase): RoutingDao = db.routingDao()
    @Provides fun provideGroupDao(db: MeshWalkDatabase): GroupDao = db.groupDao()
    @Provides fun provideSenderKeyDao(db: MeshWalkDatabase): SenderKeyDao = db.senderKeyDao()
    @Provides fun provideBlockedPeerDao(db: MeshWalkDatabase): BlockedPeerDao = db.blockedPeerDao()
    @Provides fun provideGroupArchiveDao(db: MeshWalkDatabase): GroupArchiveDao = db.groupArchiveDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindIdentityRepository(impl: IdentityRepositoryImpl): IdentityRepository

    @Binds @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds @Singleton
    abstract fun bindPeerRepository(impl: PeerRepositoryImpl): PeerRepository

    @Binds @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository

    @Binds @Singleton
    abstract fun bindRoutingRepository(impl: RoutingRepositoryImpl): RoutingRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {

    @Provides
    @Singleton
    fun provideBillingManager(@ApplicationContext context: Context): BillingManager {
        return BillingManager(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    @Binds @Singleton
    abstract fun bindCryptoManager(impl: MeshKeyManager): CryptoManagerPort
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds @Singleton
    abstract fun bindTransportManager(impl: TransportManager): TransportManagerPort
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OutboxModule {

    @Binds @Singleton
    abstract fun bindMeshOutbox(impl: MeshOutbox): MeshOutboxPort
}
