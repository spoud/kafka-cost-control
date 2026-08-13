import * as echarts from 'echarts/core';

export const CHART_THEME_LIGHT = 'kcc-light';
export const CHART_THEME_DARK = 'kcc-dark';

// A validated categorical palette (fixed hue order, checked for CVD-safe separation
// and contrast against these exact chart surfaces) so series are actually distinguishable,
// instead of every chart component hand-picking colors based on ThemeService.isDark().
// Slot 1 is the app's brand teal (see src/styles/_theme-colors.scss); slots 2-8 are
// deliberately different hues rather than more teal/blue-gray tones.
// Exported so components can build a custom legend that mirrors the same
// index-based color assignment ECharts uses internally (series/data index -> slot).
export const CHART_COLORS_LIGHT = [
    '#109AAF',
    '#eb6834',
    '#1baf7a',
    '#eda100',
    '#e87ba4',
    '#008300',
    '#4a3aa7',
    '#e34948',
];

export const CHART_COLORS_DARK = [
    '#1c9eb4',
    '#d95926',
    '#199e70',
    '#c98500',
    '#d55181',
    '#008300',
    '#9085e9',
    '#e66767',
];

const lightTheme = {
    backgroundColor: 'transparent',
    color: CHART_COLORS_LIGHT,
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
    color: CHART_COLORS_DARK,
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
