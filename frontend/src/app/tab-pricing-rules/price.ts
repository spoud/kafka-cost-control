import { Pipe, PipeTransform } from '@angular/core';
import { PriceUnit } from '../../generated/graphql/types';

const BYTES_PER_GB = 1024 * 1024 * 1024;

/** How each unit reads after "per", and how many units of a metric's raw value it covers. */
export const PRICE_UNITS: { unit: PriceUnit; label: string; valuePerUnit: number }[] = [
    { unit: PriceUnit.Gb, label: 'GB', valuePerUnit: BYTES_PER_GB },
    { unit: PriceUnit.GbHour, label: 'GB-hour', valuePerUnit: BYTES_PER_GB },
    { unit: PriceUnit.Unit, label: 'unit', valuePerUnit: 1 },
];

const unitInfo = (unit: PriceUnit) => PRICE_UNITS.find(u => u.unit === unit)!;

/** The unit a metric is usually priced in, guessed from its name. */
export function defaultPriceUnit(metricName: string): PriceUnit {
    const name = metricName.trim();
    if (name.endsWith('retained_bytes')) {
        return PriceUnit.GbHour;
    }
    return name.endsWith('bytes') ? PriceUnit.Gb : PriceUnit.Unit;
}

/** The cost per unit of the raw value, as the aggregator derives it from a price. */
export function costFactorOf(price: number, unit: PriceUnit, multiplier?: number | null): number {
    return (price * (multiplier ?? 1)) / unitInfo(unit).valuePerUnit;
}

/** The price per `unit` that a bare cost factor amounts to (rules saved before prices existed). */
export function priceFromCostFactor(costFactor: number, unit: PriceUnit): number {
    return costFactor * unitInfo(unit).valuePerUnit;
}

const money = new Intl.NumberFormat('en', { maximumSignificantDigits: 6 });

/** A free amount reads in GB, also for a per-GB-hour price (the window is the hour). */
function freeUnitLabel(unit: PriceUnit): string {
    return unit === PriceUnit.Unit ? 'unit' : 'GB';
}

export interface PricedRule {
    metricName: string;
    costFactor: number;
    price?: number | null;
    priceUnit?: PriceUnit | null;
    multiplier?: number | null;
    multiplierLabel?: string | null;
    freePerWindow?: number | null;
}

/**
 * "$0.1495 per GB", "$0.00012603 per GB-hour × 3 replicas". A rule saved with only a cost factor
 * is shown in its metric's usual unit.
 */
export function formatPrice(rule: PricedRule): string {
    if (rule.price != null && rule.priceUnit) {
        const times =
            rule.multiplier != null
                ? ` × ${money.format(rule.multiplier)}${rule.multiplierLabel ? ' ' + rule.multiplierLabel : ''}`
                : '';
        const unit = unitInfo(rule.priceUnit).label;
        const free = rule.freePerWindow
            ? `, first ${money.format(rule.freePerWindow)}${rule.priceUnit === PriceUnit.Unit ? '' : ' ' + freeUnitLabel(rule.priceUnit)} free per hour`
            : '';
        return `$${money.format(rule.price)} per ${unit}${times}${free}`;
    }
    const unit = defaultPriceUnit(rule.metricName);
    return `$${money.format(priceFromCostFactor(rule.costFactor, unit))} per ${unitInfo(unit).label}`;
}

@Pipe({ name: 'price' })
export class PricePipe implements PipeTransform {
    transform(rule: PricedRule): string {
        return formatPrice(rule);
    }
}
