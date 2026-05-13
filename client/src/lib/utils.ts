import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

// Utility function to merge class names conditionally
export const cn = (...inputs: ClassValue[]) => {
	return twMerge(clsx(inputs));
};

/** Converts underscore-separated identifiers to human-readable display names. */
export const formatDisplayName = (name: string): string =>
	name.replace(/_/g, " ");
