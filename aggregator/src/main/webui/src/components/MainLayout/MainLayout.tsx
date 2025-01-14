import { AppShell, Burger, Flex } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { Link, Outlet } from "react-router-dom";
import { Title } from "@mantine/core";
import { FaCoins } from "react-icons/fa6";

export function MainLayout() {
    const [opened, { toggle }] = useDisclosure();

    return (
        <AppShell
            header={{ height: 60 }}
            navbar={{
                width: 300,
                breakpoint: "sm",
                collapsed: { mobile: !opened },
            }}
            padding="md"
        >
            <AppShell.Header display={"flex"} p={"md"}>
                <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
                <Flex align="center" style={{ height: "100%" }} px={"sm"}>
                    <Link to={"/"} style={{ textDecoration: "none", color: "inherit" }}>
                        <Title order={2} component={"h1"}>
                            <Flex align={"center"} gap={"sm"}>
                                <FaCoins />
                                Cost Control
                            </Flex>
                        </Title>
                    </Link>
                </Flex>
            </AppShell.Header>

            <AppShell.Navbar p="md">
                <Link to={"/"}>Home</Link>
            </AppShell.Navbar>

            <AppShell.Main>
                <Outlet />
            </AppShell.Main>
        </AppShell>
    );
}
