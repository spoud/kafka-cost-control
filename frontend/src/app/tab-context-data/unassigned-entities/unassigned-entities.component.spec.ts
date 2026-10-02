import { exactRegex, unassignedLabel } from './unassigned-entities.component';

describe('exactRegex', () => {
    it('matches the name exactly, with regex characters escaped', () => {
        const regex = new RegExp(exactRegex('northstar-logistics.data.shared'));

        expect(regex.test('northstar-logistics.data.shared')).toBe(true);
        expect(regex.test('northstar-logisticsXdataXshared')).toBe(false);
        expect(regex.test('northstar-logistics.data.shared-2')).toBe(false);
    });

    it('captures the name, so $1 in a context value is the name', () => {
        expect('sa-192ddk3'.replace(new RegExp(exactRegex('sa-192ddk3')), '$1')).toBe('sa-192ddk3');
        expect(exactRegex('a+b(c)')).toBe('^(a\\+b\\(c\\))$');
    });
});

describe('unassignedLabel', () => {
    it('names the chosen key, or any context by default', () => {
        expect(unassignedLabel(null)).toBe('without any context');
        expect(unassignedLabel('cost-unit')).toBe('without cost-unit');
    });
});
