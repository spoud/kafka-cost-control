/**
 * The "group by context" picker is a single `<mat-select>`, so its form control holds one plain
 * string. `GraphFilter.groupByContext` and `Panel.groupByContext` are `string[]`, because that is
 * what the `history` query takes — and because the backend's single-key limit is a "for now"
 * (`MetricsService.getHistory`: "only support one atm"), not a shape we want baked into the UI.
 *
 * Bridging the two by hand is what broke: the form's raw string was written straight onto a panel,
 * the hydration guard demanded an array, and the panel was dropped on the next reload. These two
 * helpers are the only place that conversion is allowed to happen.
 */

/** Control value (or a legacy stored string) -> the list shape the filter and the query use. */
export function toContextKeys(value: string | string[] | undefined | null): string[] {
    if (Array.isArray(value)) {
        return value;
    }
    return value ? [value] : [];
}

/** List shape -> the single value the `<mat-select>` control can bind. */
export function toContextKeyControl(value: string | string[] | undefined | null): string {
    return (Array.isArray(value) ? value[0] : value) ?? '';
}

/** Accepts either shape, so a panel written before the normalization is repaired, not discarded. */
export function isContextKeys(value: unknown): boolean {
    return Array.isArray(value) || typeof value === 'string';
}
