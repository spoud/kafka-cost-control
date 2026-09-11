import { LegendClick } from './chart-legend/chart-legend.component';

/**
 * Legend click semantics, shared by every chart so they behave identically:
 *
 * - plain click isolates that series, and clicking the one already isolated restores all of them,
 *   so there is always a way back without hunting for a reset control
 * - shift-click toggles just that series, which is the faster gesture when one noisy series is in
 *   the way and everything else should stay put
 *
 * Returns the new set of *deselected* names — empty means everything is showing.
 */
export function applyLegendClick(
    deselected: ReadonlySet<string>,
    allNames: readonly string[],
    click: LegendClick
): ReadonlySet<string> {
    if (click.additive) {
        const next = new Set(deselected);
        if (next.has(click.name)) {
            next.delete(click.name);
        } else {
            next.add(click.name);
        }
        return next;
    }

    const visible = allNames.filter(name => !deselected.has(name));
    const alreadyIsolated = visible.length === 1 && visible[0] === click.name;
    return alreadyIsolated ? new Set() : new Set(allNames.filter(name => name !== click.name));
}
