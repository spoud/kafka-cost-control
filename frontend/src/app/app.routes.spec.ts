import { landingPath, menuLinks, routes } from './app.routes';

/**
 * The whole app is behind the sign-in (or open to all), so no route is guarded and the default
 * route always lands on Cost Overview.
 */
describe('default route', () => {
    it('lands on Cost Overview', () => {
        const empty = routes.find(r => r.path === '');
        expect((empty?.redirectTo as () => string)()).toBe('/costs');
        expect(landingPath()).toBe('/costs');
    });

    it('applies the same rule to unknown URLs', () => {
        const wildcard = routes.find(r => r.path === '**');
        expect((wildcard?.redirectTo as () => string)()).toBe('/costs');
    });

    it('guards no page: the sign-in in front of the app does', () => {
        expect(routes.filter(r => r.canActivate)).toEqual([]);
    });

    it('lists every page in the menu', () => {
        const pages = routes.filter(r => r.loadComponent).map(r => `/${r.path}`);
        expect(menuLinks.map(l => l.path).sort()).toEqual(pages.sort());
    });
});

/**
 * /graphs was renamed to /explore and /home was removed. Both would otherwise fall through to
 * the wildcard, sending a visitor following an old /graphs link to Cost Overview instead of the
 * page that link meant.
 */
describe('legacy routes', () => {
    it('sends /graphs to Explore, whoever is asking', () => {
        const graphs = routes.find(r => r.path === 'graphs');
        expect(graphs?.redirectTo).toBe('explore');
    });

    it('declares the legacy redirects before the catch-alls', () => {
        const indexOf = (path: string) => routes.findIndex(r => r.path === path);

        expect(indexOf('graphs')).toBeLessThan(indexOf(''));
        expect(indexOf('home')).toBeLessThan(indexOf(''));
        expect(indexOf('')).toBeLessThan(indexOf('**'));
    });
});
