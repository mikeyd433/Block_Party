package com.blockparty.core

import com.blockparty.core.model.Business
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.EmployeeRole
import com.blockparty.core.model.EmployeeTrait
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.npc.Staffing
import com.blockparty.core.sim.Economy
import com.blockparty.core.world.WorldFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaffingTest {
    private val looks = listOf(
        SpriteAsset("e1", SpriteKind.CHARACTER, "Apron", 10, 10, role = CharacterRole.EMPLOYEE, isStarter = true),
        SpriteAsset("e2", SpriteKind.CHARACTER, "Mine", 10, 10, role = CharacterRole.EMPLOYEE, publicEmployee = false, artistName = "You"),
        SpriteAsset("e3", SpriteKind.CHARACTER, "Theirs", 10, 10, role = CharacterRole.EMPLOYEE, publicEmployee = false, artistName = "Zed"),
    )
    private val world = WorldFactory.newWorld("You").copy(sprites = looks)

    @Test
    fun jobBoardIsStableForADayAndRespectsPrivateDrawings() {
        val a = Staffing.applicants(world, 20_000)
        assertEquals(a, Staffing.applicants(world, 20_000))
        assertTrue(a.size in 1..Staffing.APPLICANTS_PER_DAY)
        assertTrue(a != Staffing.applicants(world, 20_001), "refreshes daily")
        val looksUsed = (20_000L until 20_060L).flatMap { Staffing.applicants(world, it) }.map { it.spriteId }.toSet()
        assertTrue("e3" !in looksUsed, "someone else's private employee never applies")
        assertTrue(looksUsed.containsAll(listOf("e1", "e2")))
    }

    @Test
    fun traitsChangeWagesAndWork() {
        val fast = Staffing.wageAsk(EmployeeRole.SERVER, listOf(EmployeeTrait.FAST), false, Random(1))
        val clumsy = Staffing.wageAsk(EmployeeRole.SERVER, listOf(EmployeeTrait.CLUMSY), false, Random(1))
        assertTrue(fast > clumsy)
        val applicant = Staffing.applicants(world, 20_000).first()
        val e = applicant.hire(20_000).copy(traits = listOf(EmployeeTrait.FAST), role = EmployeeRole.SERVER)
        assertTrue(Staffing.spec(e, 12).workFactor < Staffing.spec(e.copy(traits = emptyList()), 12).workFactor)
        val owl = e.copy(traits = listOf(EmployeeTrait.NIGHT_OWL))
        assertTrue(Staffing.spec(owl, 21).workFactor < Staffing.spec(owl, 8).workFactor)
        val greeter = e.copy(role = EmployeeRole.GREETER, traits = listOf(EmployeeTrait.FRIENDLY))
        assertTrue(Staffing.greeterBoost(listOf(greeter)) > 1.3f)
    }

    @Test
    fun wagesAreBilledAndUnhappyStaffQuit() {
        val e = Staffing.applicants(world, 20_000).first().hire(20_000).copy(morale = 18, wagePerDay = 7, traits = emptyList())
        var w = world.updateLot(WorldFactory.HOME_LOT_ID) { it.copy(business = Business("Shop", employees = listOf(e))) }
        w = Economy.settle(w, 20_000).world
        val rent = Economy.rentPerDay(w.lot(WorldFactory.HOME_LOT_ID)!!, w.street)
        val s = Economy.settle(w, 20_001)
        assertEquals(100L - rent - 7, s.world.coins)
        assertTrue(s.world.lot(WorldFactory.HOME_LOT_ID)!!.business!!.employees.isEmpty(), "morale 18 - 4 = 14 -> quits")
        assertTrue(s.notices.any { "quit" in it })
        // A raise keeps them.
        val raised = Staffing.raise(e)
        assertTrue(raised.wagePerDay > e.wagePerDay && raised.morale > e.morale)
    }
}
