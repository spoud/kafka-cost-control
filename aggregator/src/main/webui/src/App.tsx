// Import styles of packages that you've installed.
// All packages except `@mantine/hooks` require styles imports
import '@mantine/core/styles.css';
import '@mantine/dates/styles.css';
import '@mantine/charts/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/code-highlight/styles.css';

import { MantineProvider } from '@mantine/core';
 
import { RouterProvider, createBrowserRouter } from 'react-router-dom';
import { MainLayout } from './components/MainLayout/MainLayout';
import { DashboardPage, loader as dashboardPageLoader } from './pages/DashboardPage/DashboardPage';

const router = createBrowserRouter([
  {
    element: <div><MainLayout /></div>, 
    children: [
  { path: '/', element: <DashboardPage />, loader: dashboardPageLoader },
  { path: '/dashboard', element: <DashboardPage /> },
    ]
  },
]);

export default function App() {
  return <MantineProvider>
    <RouterProvider router={router} />
  </MantineProvider>;
}
