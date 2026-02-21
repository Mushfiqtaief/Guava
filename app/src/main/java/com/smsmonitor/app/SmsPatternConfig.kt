package com.smsmonitor.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Simple, universal SMS pattern config.
 * Each pattern has a name, optional sender filter, optional body keyword,
 * and a list of named regex extraction fields with include/exclude toggle.
 */
data class SmsPatternConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val typeId: String = "",              // links to PatternType.id
    val senderPattern: String = "",       // sender name/number (contains match)
    val bodyKeyword: String = "",         // optional keyword in SMS body
    val extractFields: List<ExtractField> = emptyList(),
    val isBuiltIn: Boolean = false
) {

    data class ExtractField(
        val fieldName: String,      // e.g. "otp", "amount", "trxid"
        val regex: String,          // regex pattern — group 1 is extracted
        val included: Boolean = true // whether this field is active
    )

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("enabled", enabled)
            put("typeId", typeId)
            put("senderPattern", senderPattern)
            put("bodyKeyword", bodyKeyword)
            put("isBuiltIn", isBuiltIn)

            val fieldsArr = JSONArray()
            for (f in extractFields) {
                fieldsArr.put(JSONObject().apply {
                    put("fieldName", f.fieldName)
                    put("regex", f.regex)
                    put("included", f.included)
                })
            }
            put("extractFields", fieldsArr)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SmsPatternConfig {
            val fieldsArr = json.optJSONArray("extractFields")
            val fields = mutableListOf<ExtractField>()
            if (fieldsArr != null) {
                for (i in 0 until fieldsArr.length()) {
                    val obj = fieldsArr.getJSONObject(i)
                    fields.add(ExtractField(
                        fieldName = obj.optString("fieldName", ""),
                        regex = obj.optString("regex", ""),
                        included = obj.optBoolean("included", true)
                    ))
                }
            }

            return SmsPatternConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                enabled = json.optBoolean("enabled", true),
                typeId = json.optString("typeId", ""),
                senderPattern = json.optString("senderPattern", ""),
                bodyKeyword = json.optString("bodyKeyword", ""),
                extractFields = fields,
                isBuiltIn = json.optBoolean("isBuiltIn", false)
            )
        }

        val DEFAULT_BKASH = SmsPatternConfig(
            id = "builtin_bkash",
            name = "bKash",
            enabled = true,
            typeId = "type_payment",
            senderPattern = "bkash",
            bodyKeyword = "",
            extractFields = listOf(
                ExtractField("amount", "(?i)received\\s+Tk\\s+([\\d,]+\\.?\\d*)", true),
                ExtractField("trxid", "(?i)TrxID\\s+([A-Z0-9]{10})", true)
            ),
            isBuiltIn = true
        )

        val DEFAULT_NAGAD = SmsPatternConfig(
            id = "builtin_nagad",
            name = "Nagad",
            enabled = true,
            typeId = "type_payment",
            senderPattern = "nagad",
            bodyKeyword = "",
            extractFields = listOf(
                ExtractField("amount", "(?i)Amount:\\s*Tk\\s*([\\d,]+\\.?\\d*)", true),
                ExtractField("trxid", "(?i)TxnID:\\s*([A-Z0-9]+)", true)
            ),
            isBuiltIn = true
        )
    }
}
