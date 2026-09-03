package com.itmo.napoleonit.aiinterviewer.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Мелкие помощники чтения ResultSet: маппинг строк пишем руками, без магии. */

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

inline fun <reified E : Enum<E>> ResultSet.enum(column: String): E = enumValueOf(getString(column))

inline fun <reified E : Enum<E>> ResultSet.enumOrNull(column: String): E? =
    getString(column)?.let { enumValueOf<E>(it) }

fun Instant.offset(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun Instant?.offsetOrNull(): OffsetDateTime? = this?.atOffset(ZoneOffset.UTC)
