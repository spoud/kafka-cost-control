import * as echarts from 'echarts/core';

export const CHART_THEME_LIGHT = 'kcc-light';
export const CHART_THEME_DARK = 'kcc-dark';

// Tones pulled from the app's generated Material palette (see src/styles/_theme-colors.scss)
// so ECharts colors stay on-brand and in sync with the rest of the UI in both themes, instead
// of every chart component hand-picking colors based on ThemeService.isDark().
const lightTheme = {
    backgroundColor: 'transparent',
    color: [
        '#006877',
        '#36637d',
        '#456272',
        '#1c9eb4',
        '#6a96b2',
        '#7795a6',
        '#004e5a',
        '#1a4b64',
        '#2c4b59',
    ],
    textStyle: {
        color: '#171c1e',
    },
    title: {
        textStyle: { color: '#171c1e' },
    },
    legend: {
        textStyle: { color: '#171c1e' },
    },
    tooltip: {
        backgroundColor: '#f6fafb',
        borderColor: '#c2c7c9',
        textStyle: { color: '#171c1e' },
    },
    categoryAxis: {
        axisLine: { lineStyle: { color: '#c2c7c9' } },
        axisLabel: { color: '#5a5f61' },
        splitLine: { lineStyle: { color: '#edf1f3' } },
    },
    valueAxis: {
        axisLine: { lineStyle: { color: '#c2c7c9' } },
        axisLabel: { color: '#5a5f61' },
        splitLine: { lineStyle: { color: '#edf1f3' } },
    },
    sankey: {
        label: { color: '#171c1e' },
    },
    pie: {
        label: { color: '#171c1e' },
    },
};

const darkTheme = {
    backgroundColor: 'transparent',
    color: [
        '#46bacf',
        '#84b1cd',
        '#91b0c1',
        '#1c9eb4',
        '#6a96b2',
        '#7795a6',
        '#67d5ec',
        '#9fccea',
        '#accbdd',
    ],
    textStyle: {
        color: '#dfe3e5',
    },
    title: {
        textStyle: { color: '#dfe3e5' },
    },
    legend: {
        textStyle: { color: '#dfe3e5' },
    },
    tooltip: {
        backgroundColor: '#2c3133',
        borderColor: '#5a5f61',
        textStyle: { color: '#dfe3e5' },
    },
    categoryAxis: {
        axisLine: { lineStyle: { color: '#5a5f61' } },
        axisLabel: { color: '#c2c7c9' },
        splitLine: { lineStyle: { color: '#373c3e' } },
    },
    valueAxis: {
        axisLine: { lineStyle: { color: '#5a5f61' } },
        axisLabel: { color: '#c2c7c9' },
        splitLine: { lineStyle: { color: '#373c3e' } },
    },
    sankey: {
        label: { color: '#dfe3e5' },
    },
    pie: {
        label: { color: '#dfe3e5' },
    },
};

let registered = false;

export function registerChartThemes(): void {
    if (registered) {
        return;
    }
    echarts.registerTheme(CHART_THEME_LIGHT, lightTheme);
    echarts.registerTheme(CHART_THEME_DARK, darkTheme);
    registered = true;
}
