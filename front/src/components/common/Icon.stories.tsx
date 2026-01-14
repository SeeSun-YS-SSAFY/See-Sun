import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import Icon from "./Icon";

const meta = {
    title: 'Common/Icon',
    component: Icon,
    parameters: {
        layout: 'centered'
    },
    argTypes: {
        name: {
            control: 'text'
        },
        size: {
            control: 'number'
        },
        color: {
            control: 'color'
        },
        variation: {
            control: 'radio',
            options: ['outlined', 'rounded', 'sharp']
        },
        weight: {
            control: 'select',
            options: [100, 200, 300, 400, 500, 600, 700, 800, 900]
        },
        filled: {
            control: 'boolean'
        }
    }
} satisfies Meta<typeof Icon>;

export default meta;
type Story = StoryObj<typeof meta>

export const Filled: Story = {
    args: {
        name: 'check_box',
        size: 120,
        color: 'black',
        variation: 'outlined',
        weight: 800,
        filled: true
    }
}

export const Outlined: Story = {
    args: {
        name: 'check_box',
        size: 120,
        color: 'black',
        variation: 'outlined',
        weight: 800,
        filled: false
    }
}

export const Sharp: Story = {
    args: {
        name: 'check_box',
        size: 120,
        color: 'black',
        variation: 'sharp',
        weight: 800,
        filled: false
    }
}

export const Rounded: Story = {
    args: {
        name: 'check_box',
        size: 120,
        color: 'black',
        variation: 'rounded',
        weight: 800,
        filled: false
    }
}