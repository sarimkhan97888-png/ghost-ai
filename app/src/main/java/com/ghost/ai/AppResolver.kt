package com.ghost.ai

import android.content.Context
import android.content.Intent

/** Finds an installed, launchable app from a spoken name ("whatsapp", "you tube"...). */
object AppResolver {

    data class AppMatch(val label: String, val packageName: String)

    fun find(context: Context, query: String): AppMatch? {
        val q = normalize(query)
        if (q.isEmpty()) return null

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            return null
        }

        var best: AppMatch? = null
        var bestScore = Int.MIN_VALUE
        for (info in apps) {
            val pkg = info.activityInfo?.packageName ?: continue
            val label = info.loadLabel(pm)?.toString().orEmpty()
            val l = normalize(label)

            val base = when {
                l.isNotEmpty() && l == q -> 100
                l.isNotEmpty() && l.startsWith(q) -> 80
                l.isNotEmpty() && l.contains(q) -> 60
                l.length >= 3 && q.length >= 4 && q.contains(l) -> 50
                q.length >= 4 && normalize(pkg).contains(q) -> 30
                else -> 0
            }
            if (base == 0) continue

            // Prefer the closest (shortest) label when several apps match equally.
            val score = base * 100 - l.length.coerceAtMost(99)
            if (score > bestScore) {
                bestScore = score
                best = AppMatch(label.ifBlank { pkg }, pkg)
            }
        }
        return best
    }

    fun launch(context: Context, match: AppMatch): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
