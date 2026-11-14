import * as React from "react"
import { Eye, EyeOff } from "lucide-react"
import { cn } from "@/lib/utils"
import { Input } from "@/components/ui/input"

/** Password field with a show/hide (eye) toggle. Hidden by default unless `defaultVisible`. */
function PasswordInput({ className, defaultVisible = false, disabled, ...props }: Omit<React.ComponentProps<"input">, "type"> & {
  defaultVisible?: boolean
}) {
  const [visible, setVisible] = React.useState(defaultVisible)
  return (
    <div className="relative w-full">
      <Input type={visible ? "text" : "password"} className={cn("pr-11", className)} disabled={disabled}
        autoCapitalize="none" autoCorrect="off" spellCheck={false} {...props} />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        disabled={disabled}
        aria-label={visible ? "Hide password" : "Show password"}
        aria-pressed={visible}
        className="absolute inset-y-0 right-0 grid w-11 place-items-center rounded-r-md text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50"
      >
        {visible ? <EyeOff className="size-[18px]" /> : <Eye className="size-[18px]" />}
      </button>
    </div>
  )
}

export { PasswordInput }
