# Routing System Discussion

## Context

Discussion about refactoring Fulcro RAD's routing system to support statechart-controlled routing while maintaining backwards compatibility. The primary challenge is that in a statechart-based system, the statechart should be the single source of truth for application state, with the URL being a pure serialization of that state.

## Current Systems

### Fulcro RAD History (`html5_history.cljc`)
- Directly integrates with Fulcro's dynamic routing (`dr/change-route!`)
- Has "undo" capability for denied routing (puts URL back)
- Uses browser history state API with UIDs for direction detection
- Manages route listeners and can prevent routing based on conditions

### Statecharts Routing (`ui_routes.cljc`)
- Uses URL as pure data source for restoring statechart state
- Treats browser events as "desires" to change state
- Has its own history implementation (`route_history.cljc`)
- Controls URL generation based on statechart state
- Already has busy detection and route denial mechanisms

## Key Design Principles

1. **URL as Single Source of Truth**: URL contains all data needed to restore statechart position
2. **Browser Events as Intents**: Back/forward buttons express user's *desire* to navigate
3. **Statechart Controls URL**: The statechart state determines what URL should be shown
4. **Conditional Navigation**: Top-level transitions can be conditional based on current activity

## Initial Approach: Generic Routing Protocol

**User's insight**: What about using the existing HTML5 routing from RAD nearly unchanged? If we had a generic routing API (protocol) that supported `apply-route!` and `change-route!` wouldn't that make the existing history implementation work fine?

```clojure
(defprotocol RoutingSystem
  (resolve-target [system app route] "Resolve route to a UI target")
  (route-to! [system app target params] "Navigate to target with params") 
  (change-route! [system app route params] "Change route programmatically")
  (can-change-route? [system app] "Check if route change is allowed"))
```

**Problem identified**: This approach denormalizes the URL/state. The URL could get out of sync with what is on-screen, which should never happen for correct operation.

## Better Approach: Statechart-Owned URL

**Core principle**: The statechart state is the *only* source of truth, and the URL is always a serialized representation of that state.

**Flow**:
1. **Statechart owns URL**: Every state transition automatically updates the URL to reflect current statechart configuration
2. **Browser events as intents**: `popstate` events don't directly change routes - they send events like `:event/external-route-change` to the statechart
3. **Intent processing**: Statechart uses browser state (UIDs) to determine navigation direction (forward/back) and the desired target state
4. **Conditional navigation**: Statechart decides whether to honor the intent based on current conditions (form dirty, etc.)
5. **URL synchronization**: If navigation is allowed, statechart transitions and URL updates automatically; if denied, URL gets reverted

## Enforcement Strategy

**Question**: How do you enforce this, given that the user can press forward/back?

### Approach: Intercept and Revert, Then Decide

```clojure
(defn handle-popstate [evt statechart-session]
  (let [browser-state (.-state evt)
        desired-uid   (.-uid browser-state)
        current-uid   @current-uid]
    
    ;; Step 1: IMMEDIATELY revert the URL to current statechart state
    ;; (This prevents any visual flicker or temporary wrong URL)
    (.replaceState js/history current-state "" (statechart-state->url))
    
    ;; Step 2: Send intent to statechart with direction info
    (scf/send! app session-id :event/navigation-intent 
               {:desired-uid desired-uid
                :current-uid current-uid  
                :direction   (if (< desired-uid current-uid) :back :forward)})))
```

## Critical Issues Discovered

### Issue 1: Browser History Events

**Question**: Does a browser send a state event on forward?

**Answer**: Yes, the browser sends a `popstate` event for both forward and backward navigation. The `popstate` event fires whenever the user navigates through their session history, whether by clicking back/forward buttons, keyboard shortcuts, or programmatic calls.

### Issue 2: URL Parameter Extraction

**Problem**: Don't you need to track how a UID translates into a URL, since the info was stored on the URL?

**Analysis**: You need both:
1. **UID → Route mapping**: So you can determine which route the user wants to go to
2. **URL parsing**: So you can extract the statechart parameters from the URL that was stored

**Additional problem**: If you immediately revert the URL to prevent flicker, you've destroyed the URL that contained the parameters the user was trying to navigate to!

**Solution**: Extract Before Reverting
```clojure
(defn handle-popstate [evt]
  (let [event-uid     (gobj/getValueByKeys evt "state" "uid")
        ;; FIRST: Extract params from the URL before we lose it
        target-url    (.-href js/location)
        url-params    (ru/current-url-state-params target-url)]
    
    ;; THEN: Revert to prevent flicker
    (.replaceState js/history current-state "" (current-state->url))
    
    ;; Send intent with extracted params
    (scf/send! app session-id :event/navigation-intent 
               {:event-uid event-uid
                :url-params url-params
                :direction (if (< event-uid @current-uid) :back :forward)})))
```

### Issue 3: History Stack Corruption

**Problem**: Won't `replaceState` screw up the navigation history in some cases?

**Example**:
- History stack: [A, B, C] (user at C)
- User presses back → browser shows B
- We `replaceState` with C → History becomes [A, C, C]
- Browser is at position 2, but position 2 now contains C's data
- Forward button goes to position 3, which is also C
- The original B state is lost forever!

**Analysis**: The immediate revert strategy corrupts browser history by replacing the entry the user was trying to reach.

### Issue 4: Infinite Event Loops

**Problem**: Will calling `.forward()` cause another event?

**Answer**: Yes! Calling `.forward()`, `.back()`, or `.go()` will trigger another `popstate` event, creating potential infinite loops.

**Example Loop**:
1. User presses back → `popstate` event fires
2. Navigation denied → we call `.forward()`
3. `.forward()` triggers another `popstate` event
4. If still can't navigate → we call `.forward()` again
5. Infinite loop!

**Solutions**:

**Option 1: Track initiated navigation**
```clojure
(def ^:private we-initiated-navigation (atom false))

(defn handle-popstate [evt]
  (if @we-initiated-navigation
    (reset! we-initiated-navigation false)
    (if (can-navigate?)
      (sync-statechart-to-current-url)
      (do
        (reset! we-initiated-navigation true)
        (if (= direction :back) (.forward js/history) (.back js/history))))))
```

**Option 2: Use flag in history state**
```clojure
(defn undo-navigation [direction]
  (.pushState js/history #js {"undo" true} "" current-url)
  (if (= direction :back) (.forward js/history) (.back js/history)))
```

**Option 3: Remove listener temporarily**
```clojure
(defn undo-navigation [direction]
  (.removeEventListener js/window "popstate" popstate-handler)
  (if (= direction :back) (.forward js/history) (.back js/history))
  (js/setTimeout #(.addEventListener js/window "popstate" popstate-handler) 0))
```

## Key Insights

1. **Browser history management is complex**: Fighting the browser's native behavior often creates more problems than it solves
2. **Multiple sources of truth are dangerous**: URL and application state can easily get out of sync
3. **Event loops are a real concern**: Any solution that calls history methods from within popstate handlers needs careful loop prevention
4. **Parameter extraction timing matters**: You need to extract URL parameters before reverting the URL
5. **History stack integrity**: `replaceState` operations can corrupt the user's navigation history

## Open Questions

1. Is it better to let the browser manage its history naturally and sync the statechart to match?
2. How do we handle the tradeoff between preventing flicker and preserving history integrity?
3. Can we build a robust system that handles all edge cases without excessive complexity?

## Current Status

The discussion has revealed significant complexity in implementing proper statechart-controlled routing while respecting browser history behavior. The immediate revert strategy has several serious flaws that need to be addressed before implementation.