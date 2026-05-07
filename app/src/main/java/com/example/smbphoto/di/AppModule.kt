package com.example.smbphoto.di

import com.example.smbphoto.data.repository.SmbRepository
import com.example.smbphoto.data.repository.SmbRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块
 *
 * 将接口绑定到具体实现，实现依赖倒置原则。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindSmbRepository(impl: SmbRepositoryImpl): SmbRepository
}
