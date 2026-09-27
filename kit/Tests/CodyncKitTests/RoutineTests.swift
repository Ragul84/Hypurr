import Foundation
import Testing
@testable import CodyncKit

@Test func routinesDecodeMixedTriggersAndRunHistory() throws {
    let data = Data(#"{"routines":[{"id":"r","botId":"b","name":"Check","instruction":"Check status","triggers":[{"type":"cron","expression":"20 14 * * 0","timeZone":"Asia/Taipei"},{"type":"webhook"}],"enabled":true,"createdAt":0,"updatedAt":0,"triggerDescriptions":["Every Sunday","When a webhook fires"],"nextRunAt":1000}],"runs":[{"id":"run","routineId":"r","botId":"b","status":"pending","createdAt":0,"event":{"source":"schedule"}}]}"#.utf8)
    let listing = try JSONDecoder().decode(RoutineListing.self, from: data)
    #expect(listing.routines[0].triggers[0].timeZone == "Asia/Taipei")
    #expect(listing.routines[0].triggers[1].type == "webhook")
    #expect(listing.runs[0].isActive)
    #expect(listing.runs[0].rootId == nil)
}

@Test func routineTranscriptLinkSurvivesRoundTrip() throws {
    let data = Data(#"{"text":"Created routine","routineId":"r","runId":"run","style":"info"}"#.utf8)
    let entry = try JSONDecoder().decode(EntryData.self, from: data)
    let restored = try JSONDecoder().decode(EntryData.self, from: JSONEncoder().encode(entry))
    #expect(restored.routineId == "r")
    #expect(restored.runId == "run")
}

@Test func routineScheduleEditsKeepExactIntervalsAndCalendarTimes() throws {
    let interval = RoutineTrigger(type: "interval", seconds: 5400)
    let draft = RoutineScheduleDraft(triggers: [interval])
    #expect(draft.amount == "90")
    #expect(draft.unit == 60)
    #expect(try draft.triggers() == [interval])
    let weekly = RoutineTrigger(type: "cron", expression: "20 14 * * 0", timeZone: "Asia/Taipei")
    let calendar = RoutineScheduleDraft(triggers: [weekly])
    #expect(calendar.calendarStyle == "weekly")
    #expect(calendar.weekday == 0)
    #expect(try calendar.triggers() == [weekly])
    let custom = RoutineTrigger(type: "cron", expression: "*/15 9-17 * * 1-5", timeZone: "America/New_York")
    #expect(try RoutineScheduleDraft(triggers: [custom]).triggers() == [custom])
    #expect(try RoutineScheduleDraft(triggers: [weekly, interval]).triggers() == [weekly, interval])
}

@Test func routineScheduleConvertsUnitsAndRejectsOverflow() throws {
    var draft = RoutineScheduleDraft()
    draft.amount = "2"
    draft.unit = 3600
    #expect(try draft.triggers().first?.seconds == 7200)
    draft.amount = String(Int64.max)
    #expect(throws: (any Error).self) { try draft.triggers() }
    draft.amount = "0"
    #expect(throws: (any Error).self) { try draft.triggers() }
    draft.kind = "cron"
    draft.zone = "invalid/zone"
    #expect(throws: (any Error).self) { try draft.triggers() }
}

@Test func editingAnExpiredOneTimeRoutinePreservesItsTimestamp() throws {
    let once = RoutineTrigger(type: "once", at: 1_779_000_000_001)
    #expect(try RoutineScheduleDraft(triggers: [once]).triggers() == [once])
}

@Test func weekdayScheduleKeepsItsZoneAndWorkingDays() throws {
    let weekday = RoutineTrigger(type: "cron", expression: "30 8 * * 1-5", timeZone: "America/New_York")
    let draft = RoutineScheduleDraft(triggers: [weekday])
    #expect(draft.calendarStyle == "weekdays")
    #expect(try draft.triggers() == [weekday])
}
