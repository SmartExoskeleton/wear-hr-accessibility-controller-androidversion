# Technical Documentation: Wear HR Adaptive Controller

This repository contains a two-part Android system built around a simple idea: use heart-rate data collected on a Wear OS device to drive mode selection logic on a paired Android phone.

## 0. Stack Summary

The repository composes multiple Android and Google Play Services subsystems.

The main technical layers used are:

- Wear OS sensor framework
- Android runtime permission model
- Android activity and service lifecycle
- Android foreground-service model
- Google Play Services Wearable API
- Kotlin coroutines and `StateFlow`
- Jetpack Compose / Wear Compose
- Android accessibility framework

From a systems perspective, the end-to-end stack looks like this:

1. Physical sensing layer: optical heart-rate sensor on the watch
2. Device framework layer: `SensorManager` and `SensorEventListener`
3. Background execution layer: watch foreground service
4. Inter-device transport layer: Wearable Message API
5. Phone ingress layer: `WearableListenerService`
6. Shared state layer: `HrStore`
7. Control layer: `ControlEngine`
8. Orchestration layer: `HrAutomationController`
9. UI execution layer: `ModeAccessibilityService`
10. External target layer: target app accessibility node tree

## 1. Project Shape

The repository is split into two Android application modules:

- [`app`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app): phone-side controller and execution layer
- [`wear`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/wear): watch-side sensing and telemetry layer

The watch is responsible for sensing.
The phone is responsible for interpreting and acting.

Sensor access is handled on the watch. Mode execution is handled on the phone through the accessibility stack.

## 2. Architectural Intent

The path is:

1. The watch acquires `TYPE_HEART_RATE` sensor samples.
2. The watch serializes BPM values into small wearable messages.
3. The phone receives those messages in the background.
4. The phone stores the latest BPM as shared application state.
5. The phone feeds BPM samples into a controller state machine.
6. The controller decides whether to hold or request a switch.
7. If a switch is requested, the execution layer validates the runtime conditions.
8. The accessibility service locates and clicks the target mode control in the controlled app.

The system contains three different notions of "state":

- physical state: the wearer’s measured heart rate,
- logical state: the controller’s current mode and transition candidate,
- UI execution state: whether the target application is in a state where a mode switch can actually be applied.

## 2A. Protocol and Transport

The watch-phone communication path uses the Google Play Services Wearable API. the repository uses:

- `Wearable.getNodeClient(...)`
- `Wearable.getMessageClient(...)`
- `WearableListenerService`

The communication path does not use raw Bluetooth, sockets, or HTTP. The watch publishes application-level messages over the Wearable transport provided by Google Play Services, and the phone receives them through the corresponding listener service.

### 2A.1 Message protocol

- route key: message path `"/hr"`
- payload body: BPM represented as a string and then encoded as bytes

The protocol is an application convention layered on top of the Wearable Message API:

- routing by path,
- interpretation by payload format.

### 2A.2 Protocol characteristics

The system pushes a small, short-lived, latest-value signal from watch to phone. The protocol characteristics are:

- the payload is tiny,
- serialization overhead stays near zero,
- the receiver only needs the newest BPM value,


### 2A.3 Tradeoffs of the chosen protocol

This approach is simple, but it comes with technical limits:

- no explicit app-level acknowledgement
- no sequence numbering
- no sender timestamp
- no payload schema evolution strategy
- no replay or recovery mechanism

## 3. Module-Level Technical Tree

### 3.1 Wear module

Primary files:

- [`heartratewatchapp.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/wear/src/main/java/com/example/heartratemonitor/wear/presentation/heartratewatchapp.kt)
- [`HeartRateForegroundService.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/wear/src/main/java/com/example/heartratemonitor/wear/service/HeartRateForegroundService.kt)
- [`wear/src/main/AndroidManifest.xml`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/wear/src/main/AndroidManifest.xml)

Responsibilities:

- permission acquisition,
- sensor resolution,
- real-time heart-rate sampling,
- background-safe streaming to the phone.

### 3.2 Phone module

Primary files:

- [`HrReceiverService.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrReceiverService.kt)
- [`HrStore.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrStore.kt)
- [`HrAutomationController.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrAutomationController.kt)
- [`ControlEngine.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/ControlEngine.kt)
- [`ModeAccessibilityService.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/ModeAccessibilityService.kt)
- [`MainActivity.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/MainActivity.kt)

Responsibilities:

- background message ingestion,
- shared heart-rate state persistence,
- state-machine based decision making,
- runtime gating and switch execution,
- limited operator control and inspection.

The phone side is split into narrow layers:

- reception,
- storage,
- orchestration,
- pure control logic,
- execution.

## 4. Data Flow and Control Flow

### 4.1 Data flow

The watch produces BPM values.

Those values travel:

`SensorManager` -> `HeartRateForegroundService` -> Wearable Message API -> `HrReceiverService` -> `HrStore`

At that point the sample becomes phone-side application state.

### 4.2 Control flow

Once the BPM is inside `HrStore`, the system changes from transport behavior to control behavior:

`HrStore` -> `HrAutomationController` -> `ControlEngine` -> `ModeAccessibilityService`

The first half of the system is a telemetry pipeline. The second half is a controller-executor pipeline.

Failures in each half have different meanings:

- a transport failure means the phone never got the sample,
- a control failure means the sample arrived but the logic did not request a switch,
- an execution failure means the logic requested a switch but Android UI automation could not complete it.

## 5. Watch-Side Sensing Methodology

### 5.1 Permission strategy

In [`heartratewatchapp.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/wear/src/main/java/com/example/heartratemonitor/wear/presentation/heartratewatchapp.kt), the app checks for:

- `android.permission.health.READ_HEART_RATE`
- `android.permission.BODY_SENSORS`

The code does not assume a single permission model because Wear/Android platform combinations can differ. Instead, it checks which relevant permission is declared on the current device and works with whatever is available

### 5.2 Sensor selection strategy

The watch tries to resolve `Sensor.TYPE_HEART_RATE`, preferring a wake-up sensor when one is available.

The reason is straightforward:

- a wake-up sensor is better aligned with persistent monitoring,
- if such a sensor is not available, any valid heart-rate sensor is still better than failing entirely.

The watch uses a single-sensor selection strategy.

### 5.3 Why both activity and service listen for HR

The activity and the foreground service both touch the heart-rate sensor, but they do so for different reasons.

The activity exists to:

- verify permissions,
- establish that sensing is viable,
- surface current status locally.

The foreground service exists to:

- continue monitoring when the activity is backgrounded,
- provide durable streaming behavior,
- meet platform expectations for long-running sensor work.

The service is the transport backbone even though the activity also observes sensor events.

### 5.4 Sampling and transmission behavior

In [`HeartRateForegroundService.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/wear/src/main/java/com/example/heartratemonitor/wear/service/HeartRateForegroundService.kt), the service:

- rejects non-heart-rate events,
- rejects non-positive BPM values,
- rate-limits outbound transmission to roughly one sample per second,
- sends the BPM to each connected node using the Wearable Message API.

The payload is the BPM string itself.

Protocol consequences:

- the message is tiny,
- serialization overhead stays negligible,
- the receiving side does not need schema decoding,
- the control loop only requires the latest value.

### 5.5 Watch-side Android layers involved

The watch code crosses several Android layers in a very deliberate order:

- permission layer: determines whether the app is allowed to access heart-rate data
- activity layer: manages permission requests and local state exposure
- sensor layer: subscribes to `TYPE_HEART_RATE`
- service layer: keeps monitoring alive outside the activity lifecycle
- notification layer: satisfies foreground-service policy
- wearable transport layer: forwards BPM to the phone

## 6. Phone-Side Ingestion Layer

The phone-side entry point for watch messages is [`HrReceiverService.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrReceiverService.kt).

This is a `WearableListenerService`.

The service logic is intentionally strict:

- ignore any message whose path is not `"/hr"`,
- decode the message body into a string,
- hand the value to `HrStore`.


### 6.1 Why background reception matters

The phone can still ingest BPM values when:

- the main activity is not open,
- the phone app is backgrounded,
- the operator is not interacting with the dashboard.

## 7. Shared State Layer

[`HrStore.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrStore.kt) is the bridge between raw incoming data and the controller.

It does three things:

1. keeps the latest heart-rate value in memory through `StateFlow`,
2. persists that value to `SharedPreferences`,
3. forwards valid integer BPM values into the automation controller.

It also performs a lightweight de-duplication step, ignoring rapid repeats of the same value inside a short window.
The de-duplication step reduces repeated control-loop evaluation on identical bursts.


## 7A. Reactive State Propagation

Inside the phone app, state propagation happens through Kotlin flows rather than direct UI callbacks.

This creates two distinct propagation modes in the overall system:

- inter-device propagation: watch to phone through wearable messages
- intra-process propagation: phone-side state through `StateFlow`


## 8. Control Architecture

The control side was built in two layers:

- [`ControlEngine.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/ControlEngine.kt): pure state machine
- [`HrAutomationController.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrAutomationController.kt): Android-facing orchestration layer

The split is:

- `ControlEngine` decides,
- `HrAutomationController` coordinates.

## 9. State Machine Design

The state machine is defined in [`ControlEngine.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/ControlEngine.kt).

### 9.1 Domain model

The engine models:

- current mode,
- candidate mode,
- candidate start time,
- last successful switch time,
- manual hold-until time.

The exposed decision model is `EngineDecision`, which contains:

- a message,
- an optional `switchTarget`,
- a reason code.

### 9.2 Modes

The mode enum is simple:

- `ECO`
- `FITNESS`
They are explicit states in a finite-state machine.

### 9.3 Configuration parameters

The controller behavior is shaped by `ControlConfig`:

- `highThreshold`
- `lowThreshold`
- `dwellHighMs`
- `dwellLowMs`
- `cooldownMs`
- `manualOverrideHoldMs`

The controller is a discrete mode controller with temporal hysteresis.

### 9.4 Why hysteresis was necessary

Heart rate is not stable enough for naive threshold switching.

Immediate switching on a single threshold would increase oscillation near the threshold boundary.

The implemented method avoids that by using:

- separate low and high thresholds,
- dwell time before confirming a mode transition,
- cooldown after a successful switch,
- manual hold after explicit operator intervention.

### 9.4A Temporal state, not just logical state

The controller is driven by BPM plus time. The machine state includes:

- current mode
- candidate mode
- when the candidate began
- when the last successful switch happened
- until when manual override suppresses auto behavior

### 9.5 State progression

For each incoming BPM sample, the engine proceeds conceptually like this:

1. Validate the sample.
2. Check whether automation is enabled.
3. Check whether manual override hold is active.
4. Compute the desired mode from the current mode and BPM.
5. If desired mode equals current mode, clear transition candidacy.
6. If desired mode differs from current mode and differs from the current candidate, start a new candidate.
7. If the candidate exists but dwell time has not elapsed, remain in waiting state.
8. If dwell has elapsed, check cooldown.
9. If cooldown is satisfied, emit a switch request.

The controller has implicit sub-states even though they are not declared as a separate enum:

- hold,
- candidate started,
- dwell wait,
- cooldown blocked,
- switch request,
- manual hold,
- auto off.

### 9.6A Equivalent conceptual states

Even though the code does not model every state as a dedicated enum, the engine can still be read as a finite set of operational states:

- `Stable(ECO)`
- `Stable(FITNESS)`
- `Candidate(ECO)`
- `Candidate(FITNESS)`
- `ManualHold`
- `CooldownBlocked`
- `AutoDisabled`

The implementation stores those states as combinations of fields=

### 9.6 Desired mode mapping

One detail that matters when reading the code:

- when current mode is `ECO`, low BPM may request `FITNESS`,
- when current mode is `FITNESS`, high BPM may request `ECO`.

The naming is tied to the semantics of the target application.

## 10. Controller Orchestration

[`HrAutomationController.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrAutomationController.kt) is where the pure decision layer is integrated into Android runtime behavior.

The object maintains:

- current mode,
- auto-enabled state,
- current config,
- latest decision text,
- switch-test state,
- pending manual mode requests.


### 10.1 Automatic path

On each valid BPM sample:

1. the controller asks `ControlEngine` for a decision,
2. if no switch is requested, the reason is kept as the latest decision state,
3. if a switch is requested, the controller validates execution prerequisites,
4. if prerequisites pass, it asks `ModeAccessibilityService` to perform the switch,
5. on success, it updates both the engine and the externally visible current mode.

A controller decision does not imply execution. Execution remains conditional.

### 10.2 Manual and semi-manual paths

The orchestration layer also contains:

- forced mode requests,
- an emergency stop,
- a timed alternating switch test,
- manual override behavior that creates a temporary hold window.

`HrAutomationController` acts as the policy and orchestration layer around the core state machine.

## 10A. Execution Pipeline

When the system receives a BPM sample and ultimately performs a switch, the technical call chain is:

1. `HrReceiverService.onMessageReceived(...)`
2. `HrStore.update(...)`
3. `HrAutomationController.onHeartRateSample(...)`
4. `ControlEngine.onSample(...)`
5. `ModeAccessibilityService.performModeSwitch(...)`
6. `AccessibilityNodeInfo.performAction(ACTION_CLICK)`

This is the primary runtime execution path in the repository.

## 11. Accessibility Execution Layer

The execution layer is implemented in [`ModeAccessibilityService.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/ModeAccessibilityService.kt).

This layer interacts with the external controlled app.

### 11.1 Why accessibility was used

The target app does not appear to expose a direct integration API for mode switching. Because of that, the only viable runtime mechanism is UI automation.

Android accessibility provides the primitives needed to:

- inspect the active window,
- search the node tree,
- identify candidate controls,
- perform click actions.

The project performs UI injection through the Android accessibility action channel.

The injection here is behavioral:

- inspect the target app's accessibility tree,
- resolve the node that represents the desired control,
- synthesize an accessibility click action against that node or its parent.

The controlled app is influenced through framework accessibility actions rather than a direct SDK integration.

### 11.2 Execution strategy

Before a switch is attempted, the code checks:

- is the accessibility service enabled,
- is the service actually bound,
- is the target app the current foreground package,
- can a node matching the requested mode be found,
- is that node or its parent clickable.

If any of those checks fail, the switch is blocked.

### 11.2A How UI injection actually happens

The UI execution sequence is:

1. The accessibility service is enabled in Android settings.
2. The service stays bound and can inspect `rootInActiveWindow`.
3. The controller requests a switch to `ECO` or `FITNESS`.
4. The service verifies that the active package matches the expected target package.
5. The service walks the active accessibility node tree.
6. It first tries to match view-id resource name hints.
7. If that fails, it falls back to text and content-description matching.
8. It prefers nodes that look actionable, such as button/tab/chip-like controls.
9. It tries `performAction(ACTION_CLICK)` on the node.
10. If the node itself is not clickable, it walks upward to a clickable parent and performs the action there.

Execution depends on the target app's runtime view hierarchy as well as the controller logic.

### 11.3 Node targeting methodology

The lookup method is layered:

1. try view-id resource name hints,
2. if needed, try text and content-description matching,
3. prefer nodes that look like buttons, tabs, or chips,
4. click the node directly if possible,
5. otherwise climb up the parent chain and click the nearest actionable parent.

### 11.3A Selector model

The node-resolution logic is heuristic by design. It combines:

- structural hints: `viewIdResourceName`
- semantic hints: visible text and content description
- behavioral hints: clickability on the node or its parent
- UI-role hints: class names that resemble buttons, tabs, or chips

The selector model remains dependent on the target application's UI structure.

## 12. Android Components and Framework Layers Used

### 12.1 Activity layer

- watch activity for permission handling and local watch-side state display
- phone activity for visibility into controller state and limited runtime control

### 12.2 Service layer

- watch foreground service for persistent sensing and transmission
- phone wearable listener service for background message ingestion
- phone accessibility service for target-app execution

### 12.3 Sensor layer

- `SensorManager`
- `Sensor`
- `SensorEventListener`

### 12.4 Concurrency and state layer

- Kotlin coroutines
- `CoroutineScope`
- `Job`
- `MutableStateFlow`
- `StateFlow`

### 12.5 Persistence layer

- `SharedPreferences`

### 12.6 Rendering layer

- Jetpack Compose
- Wear Compose Material

### 12.7 Inter-device transport layer

- Google Play Services Wearable Message API

### 12.8 Accessibility layer

- `AccessibilityService`
- `AccessibilityNodeInfo`
- `AccessibilityEvent`

## 13. Runtime Gating Logic

One of the main methodological decisions in this project was to separate "should switch" from "can switch right now."

Those are not the same question.

The controller may conclude that a switch is logically correct, but the system still refuses to act unless:

- accessibility is enabled,
- the target app is on screen,
- the correct control can be found.

The automation pipeline keeps control intent and execution feasibility as separate checks.

## 14. Configuration Strategy

Controller parameters are persistent and normalized in [`HrAutomationController.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/main/java/com/example/heartratemonitor/HrAutomationController.kt).

Normalization rules ensure:

- thresholds stay inside reasonable ranges,
- low threshold stays below high threshold,
- time values do not collapse to invalid durations.


## 15. Failure Boundaries

The architecture naturally creates several distinct failure boundaries:

- sensing failure: no heart-rate sensor or no valid sample lock
- permission failure: sensor access denied
- transport failure: BPM not delivered to the phone
- storage/state failure: sample not propagated as expected
- control failure: controller decides to hold
- gating failure: accessibility disabled or target app not foreground
- selector failure: target node cannot be found
- action failure: click action returns failure

This matters because "the app did not switch" is not one technical problem. It can fail at several layers, and the structure maps closely to those failure categories.

## 16. Testability and Verification

The pure controller is covered by deterministic unit tests in [`ControlEngineTest.kt`](/Users/nbal0029/AndroidStudioProjects/Heartratemonitor/app/src/test/java/com/example/heartratemonitor/ControlEngineTest.kt).

The tests cover:

- waiting before dwell expires,
- switching after dwell expires,
- blocking immediate reversal through cooldown,
- honoring the manual override hold window.

The timing behavior of the control state machine is covered by unit tests.

The accessibility execution path is more integration-dependent and is not covered in the same way.

## 17. Developed By

Developed by `ravishan_n` on `2026-03-09`.
