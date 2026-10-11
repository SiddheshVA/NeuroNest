
import Link from "next/link";
import { Menu } from "lucide-react";
import { useUser, UserButton } from "@clerk/nextjs";
import { usePathname } from "next/navigation";

import { Button, buttonVariants } from "@/components/ui/button";
import {
  Sheet,
  SheetTrigger,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetClose,
} from "@/components/ui/sheet";
import { isNavLinkActive, mainNavLinks } from "@/lib/site-config";
import { cn } from "@/lib/utils";
import { Logo } from "@/components/layout/logo";

function MobileNav() {
  const { isSignedIn } = useUser();
  const pathname = usePathname();

  return (
    <Sheet>
      <SheetTrigger
        render={
          <Button variant="ghost" size="icon" aria-label="Open menu" className="xl:hidden" />
        }
      >
        <Menu aria-hidden="true" />
      </SheetTrigger>
      <SheetContent side="right" className="w-3/4 px-0">
        <SheetHeader className="px-4">
          <SheetTitle render={<Logo />} />
        </SheetHeader>
        <nav className="flex flex-col gap-1 px-4" aria-label="Mobile">
          {mainNavLinks.map((link) => {
            const isActive = isNavLinkActive(link.href, pathname);
            return (
              <SheetClose
                key={link.href}
                nativeButton={false}
                render={<Link href={link.href} aria-current={isActive ? "page" : undefined} />}
                className={cn(
                  "block rounded-lg px-3 py-2.5 text-base font-medium transition-colors",
                  isActive ? "bg-[#E2E6DD] text-[#1C352D]" : "text-foreground hover:bg-muted",
                )}
              >
                {link.label}
              </SheetClose>
            );
          })}
        </nav>
        <div className="mt-auto flex flex-col gap-2 border-t border-border px-4 py-4">
          {isSignedIn ? (
            <>
              <SheetClose
                nativeButton={false}
                render={<Link href="/dashboard" />}
                className={buttonVariants({ size: "lg", className: "w-full" })}
              >
                Dashboard
              </SheetClose>
              <div className="flex items-center justify-center py-1">
                <UserButton />
              </div>
            </>
          ) : (
            <>
              <SheetClose
                nativeButton={false}
                render={<Link href="/sign-up" />}
                className={buttonVariants({ size: "lg", className: "w-full" })}
              >
                Get Started
              </SheetClose>
              <SheetClose
                nativeButton={false}
                render={<Link href="/login" />}
                className={buttonVariants({
                  variant: "outline",
                  size: "lg",
                  className: "w-full",
                })}
              >
                Log In
              </SheetClose>
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}

export { MobileNav };
