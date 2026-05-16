package com.example.smbphoto.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * ImageIndex DAO - Room 数据库访问对象
 *
 * 提供图片索引的 CRUD 操作和分页查询
 */
@Dao
interface ImageIndexDao {

    /**
     * 分页查询图片（按拍摄时间倒序）
     *
     * 排序逻辑：优先使用 takenAt，takenAt=0 时回退到 lastModified
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @param limit 每页数量
     * @param offset 偏移量
     * @return 图片索引列表
     */
    @Query("""
        SELECT * FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
        ORDER BY
            CASE WHEN takenAt > 0 THEN takenAt ELSE lastModified END DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getImagesPaged(
        serverIp: String,
        shareName: String,
        albumPath: String,
        limit: Int,
        offset: Int
    ): List<ImageIndex>

    /**
     * 查询该相簿所有图片路径（用于增量同步比对）
     *
     * @return 所有文件路径的列表
     */
    @Query("""
        SELECT filePath FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
    """)
    suspend fun getAllFilePaths(
        serverIp: String,
        shareName: String,
        albumPath: String
    ): List<String>

    /**
     * 统计相簿图片总数
     *
     * @return 图片数量
     */
    @Query("""
        SELECT COUNT(*) FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
    """)
    suspend fun getImageCount(
        serverIp: String,
        shareName: String,
        albumPath: String
    ): Int

    /**
     * 查询该相簿所有图片（按拍摄时间倒序）
     *
     * 第二次进入相簿时使用，一次性返回所有图片
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @return 所有图片索引列表
     */
    @Query("""
        SELECT * FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
        ORDER BY
            CASE WHEN takenAt > 0 THEN takenAt ELSE lastModified END DESC
    """)
    suspend fun getAllImages(
        serverIp: String,
        shareName: String,
        albumPath: String
    ): List<ImageIndex>

    /**
     * 批量插入/更新索引
     *
     * 使用 REPLACE 策略：如果主键冲突则替换
     *
     * @param images  图片索引列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ImageIndex>)

    /**
     * 插入单条索引
     *
     * @param image 图片索引
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageIndex)

    /**
     * 按路径删除单条索引（用于增量同步删除）
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @param filePath 文件路径
     */
    @Query("""
        DELETE FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
        AND filePath = :filePath
    """)
    suspend fun deleteByPath(serverIp: String, shareName: String, albumPath: String, filePath: String)

    /**
     * 删除指定相簿的所有索引
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     */
    @Query("""
        DELETE FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
    """)
    suspend fun deleteAlbum(serverIp: String, shareName: String, albumPath: String)

    /**
     * 删除指定服务器的索引（断开连接时清理）
     *
     * @param serverIp 服务器 IP
     */
    @Query("DELETE FROM image_index WHERE serverIp = :serverIp")
    suspend fun deleteServer(serverIp: String)

    /**
     * 查询单条索引的拍摄时间（用于检查是否已缓存）
     *
     * @return takenAt 值，如果不存在返回 null
     */
    @Query("""
        SELECT takenAt FROM image_index
        WHERE serverIp = :serverIp
        AND shareName = :shareName
        AND albumPath = :albumPath
        AND filePath = :filePath
    """)
    suspend fun getCachedTakenAt(
        serverIp: String,
        shareName: String,
        albumPath: String,
        filePath: String
    ): Long?
}
