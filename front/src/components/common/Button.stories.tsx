import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import Button from "./Button";

const meta = {
    title: 'Common/Button',
    component: Button,
    parameters: {
        layout: 'centered'
    },
    argTypes: {
        children: {
            control: 'text'
        },
        disabled: {
            control: 'boolean'
        }
    }
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>

export const Default: Story = {
    args: {
        children: 'Default Button',
    }
}

export const Disabled: Story = {
    args: {
        children: 'Disabled Button',
        disabled: true,
    }
}