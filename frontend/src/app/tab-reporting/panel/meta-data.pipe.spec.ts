import { IntlDateService } from '../../services/intl-date.service';
import { Panel } from '../panel.type';
import { formatPanelMeta } from './meta-data.pipe';

const dates = { transform: (d: Date | string) => String(d).slice(0, 10) } as IntlDateService;

const panel: Panel = {
    id: 'p1',
    title: 'Network write',
    type: 'StackedBar',
    from: '2026-10-01T00:00:00Z' as unknown as Date,
    metricName: 'confluent_kafka_server_request_bytes',
    groupByContext: ['tenant'],
};

describe('formatPanelMeta', () => {
    it('says when a panel shows the cost, here and in the PDF export', () => {
        expect(formatPanelMeta(panel, dates)).toBe(
            '(confluent_kafka_server_request_bytes, 2026-10-01 - now and grouped by tenant)'
        );
        expect(formatPanelMeta({ ...panel, showCost: true }, dates)).toBe(
            '(cost of confluent_kafka_server_request_bytes, 2026-10-01 - now and grouped by tenant)'
        );
    });
});
