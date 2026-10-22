import { CalendarClock } from "lucide-react";
import { UserButton } from "@clerk/nextjs";

/** The signed-in user's account menu (Clerk's UserButton) with the user's own pages added. */
function AccountMenu() {
  return (
    <UserButton>
      <UserButton.MenuItems>
        <UserButton.Link
          label="My appointment requests"
          labelIcon={<CalendarClock className="size-4" aria-hidden="true" />}
          href="/appointments"
        />
      </UserButton.MenuItems>
    </UserButton>
  );
}

export { AccountMenu };
