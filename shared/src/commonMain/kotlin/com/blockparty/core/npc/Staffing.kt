package com.blockparty.core.npc

import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.Employee
import com.blockparty.core.model.EmployeeRole
import com.blockparty.core.model.EmployeeTrait
import com.blockparty.core.model.LotState
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.WorldState
import kotlin.math.roundToLong
import kotlin.random.Random

/** Someone looking for work on today's job board. Everything but the look is chosen by the game. */
data class Applicant(
    val id: String,
    val spriteId: String,
    val name: String,
    val role: EmployeeRole,
    val traits: List<EmployeeTrait>,
    val wageAsk: Long,
    /** Rare applicants start more experienced, with only good traits, and ask for more. */
    val rare: Boolean,
) {
    fun hire(day: Long) = Employee(
        id = id, spriteId = spriteId, name = name, role = role, traits = traits,
        wagePerDay = wageAsk, hiredDay = day, xp = if (rare) 150 else 0, morale = 80,
    )
}

/** How a member of staff behaves in the shop right now (from role, traits, level and the hour). */
data class StaffSpec(
    val id: String,
    /** Null for the shopkeeper (the player), who can do a bit of everything. */
    val role: EmployeeRole?,
    val walkFactor: Float = 1f,
    /** Multiplies how long a service or a payment takes. */
    val workFactor: Float = 1f,
    /** Star adjustment for customers they serve or ring up. */
    val reviewBonus: Float = 0f,
) {
    val canServe: Boolean get() = role == null || role == EmployeeRole.SERVER
}

object Staffing {
    /** Most employees one shop can have. */
    const val MAX_EMPLOYEES = 4
    const val APPLICANTS_PER_DAY = 4
    const val RARE_CHANCE = 0.12f
    const val QUIT_MORALE = 15
    const val RAISE_SHARE = 0.15f
    const val RAISE_MORALE = 25

    private val names = listOf(
        "Arlo", "Bee", "Cass", "Dara", "Ezra", "Flo", "Gil", "Hana", "Ike", "Juno", "Kip", "Lux",
        "Moss", "Nia", "Oz", "Pia", "Quill", "Remy", "Sol", "Tam", "Uri", "Vale", "Wynn", "Yara",
    )

    private val goodTraits = listOf(EmployeeTrait.FAST, EmployeeTrait.FRIENDLY, EmployeeTrait.CAREFUL, EmployeeTrait.STEADY)

    /** Drawings that can apply for jobs: employee drawings (public, or private ones by this player). */
    fun applicantLooks(world: WorldState): List<SpriteAsset> =
        world.sprites.filter { it.role == CharacterRole.EMPLOYEE && (it.publicEmployee || it.artistName == world.playerName || it.isStarter) }

    /** Today's job board. The same day always gives the same applicants. */
    fun applicants(world: WorldState, day: Long, streetSeed: String = world.street.id): List<Applicant> {
        val looks = applicantLooks(world)
        if (looks.isEmpty()) return emptyList()
        val hired = world.street.lots.flatMap { it.business?.employees.orEmpty() }.map { it.id }.toSet()
        val random = Random(NpcGenerator.seedOf("$streetSeed/jobs/$day"))
        return (0 until APPLICANTS_PER_DAY).map { i ->
            val rare = random.nextFloat() < RARE_CHANCE
            val role = EmployeeRole.entries[random.nextInt(EmployeeRole.entries.size)]
            val traits = if (rare) goodTraits.shuffled(random).take(2)
            else EmployeeTrait.entries.shuffled(random).take(1 + random.nextInt(2)).let(::withoutClashes)
            Applicant(
                id = "emp-$day-$i",
                spriteId = looks[random.nextInt(looks.size)].id,
                name = names[random.nextInt(names.size)],
                role = role,
                traits = traits,
                wageAsk = wageAsk(role, traits, rare, random),
                rare = rare,
            )
        }.filter { it.id !in hired }
    }

    private fun withoutClashes(traits: List<EmployeeTrait>): List<EmployeeTrait> {
        val clashes = listOf(
            setOf(EmployeeTrait.NIGHT_OWL, EmployeeTrait.EARLY_BIRD),
            setOf(EmployeeTrait.CAREFUL, EmployeeTrait.CLUMSY),
            setOf(EmployeeTrait.FAST, EmployeeTrait.CAREFUL),
        )
        return if (clashes.any { traits.toSet().containsAll(it) }) traits.take(1) else traits
    }

    fun wageAsk(role: EmployeeRole, traits: List<EmployeeTrait>, rare: Boolean, random: Random): Long {
        var wage = when (role) {
            EmployeeRole.CASHIER -> 6f
            EmployeeRole.STOCKER -> 5f
            EmployeeRole.SERVER -> 8f
            EmployeeRole.GREETER -> 4f
        }
        for (t in traits) wage += when (t) {
            EmployeeTrait.FAST -> 2f
            EmployeeTrait.FRIENDLY -> 1.5f
            EmployeeTrait.CAREFUL -> 1f
            EmployeeTrait.STEADY -> 1f
            EmployeeTrait.CHATTY -> -1f
            EmployeeTrait.CLUMSY -> -2f
            EmployeeTrait.NIGHT_OWL, EmployeeTrait.EARLY_BIRD -> 0f
        }
        wage += random.nextInt(4)
        if (rare) wage *= 1.5f
        return wage.roundToLong().coerceAtLeast(2)
    }

    /** How an employee works at [hour] (0..23 in the street's timezone). */
    fun spec(e: Employee, hour: Int): StaffSpec {
        var walk = 1f
        var work = 1f - 0.06f * (e.level - 1).coerceAtMost(5) // experience makes them quicker
        var bonus = 0f
        for (t in e.traits) when (t) {
            EmployeeTrait.FAST -> { walk *= 1.3f; work *= 0.75f }
            EmployeeTrait.FRIENDLY -> bonus += 0.3f
            EmployeeTrait.CAREFUL -> work *= 1.1f
            EmployeeTrait.CHATTY -> { work *= 1.2f; bonus += 0.2f }
            EmployeeTrait.NIGHT_OWL -> if (hour >= 18 || hour < 2) work *= 0.8f else if (hour in 6..11) work *= 1.15f
            EmployeeTrait.EARLY_BIRD -> if (hour in 6..11) work *= 0.8f else if (hour >= 18) work *= 1.15f
            EmployeeTrait.CLUMSY, EmployeeTrait.STEADY -> Unit
        }
        if (e.morale < 35) work *= 1.15f // unhappy staff drag their feet
        return StaffSpec(e.id, e.role, walk, work, bonus)
    }

    /** Extra pull a shop's greeters give passers-by (1 = none). */
    fun greeterBoost(employees: List<Employee>): Float = 1f + employees.filter { it.role == EmployeeRole.GREETER }
        .sumOf { e -> (0.25 + 0.05 * (e.level - 1) + if (EmployeeTrait.FRIENDLY in e.traits) 0.1 else 0.0) }.toFloat()

    /** Chance a stocker wastes a unit when restocking. */
    fun wasteChance(e: Employee): Float = when {
        EmployeeTrait.CAREFUL in e.traits -> 0f
        EmployeeTrait.CLUMSY in e.traits -> 0.25f
        else -> 0.03f
    }

    /**
     * One working day for every employee: experience goes up, morale drifts down (less for
     * steady ones), and anyone whose morale is too low quits.
     */
    fun dailyUpdate(world: WorldState): Pair<WorldState, List<String>> {
        val notices = ArrayList<String>()
        var w = world
        for (lot in world.street.lots) {
            val b = lot.business ?: continue
            if (lot.state != LotState.OWNED || b.employees.isEmpty()) continue
            val updated = b.employees.map { e ->
                val decay = if (EmployeeTrait.STEADY in e.traits) 2 else 4
                e.copy(xp = e.xp + 10, morale = (e.morale - decay).coerceIn(0, 100))
            }
            val quitting = updated.filter { it.morale <= QUIT_MORALE }
            for (q in quitting) notices += "${q.name} quit ${b.name.ifBlank { "your shop" }}: morale was too low. A raise keeps staff happy."
            w = w.updateLot(lot.id) { it.copy(business = b.copy(employees = updated - quitting.toSet())) }
        }
        return w to notices
    }

    /** A raise: wage up 15%, morale up a lot. */
    fun raise(e: Employee): Employee = e.copy(
        wagePerDay = (e.wagePerDay * (1 + RAISE_SHARE)).roundToLong().coerceAtLeast(e.wagePerDay + 1),
        morale = (e.morale + RAISE_MORALE).coerceAtMost(100),
    )
}
